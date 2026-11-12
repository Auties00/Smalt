/* Ports SVT-JPEG-XS 0.9.0 (Mct.c, NltDec.c). Copyright 2024 Intel Corporation; BSD-2-Clause-Patent.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxs.internal;

import com.github.auties00.smalt.MediaException;

/**
 * The multiple component transformations (ISO/IEC 21122-1 annex F) and the output scaling with its nonlinearities
 * (annex G) of JPEG XS.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
public final class JxsTransforms {
    /**
     * The displacement of each component by CFA pattern type, {@code [type][component] = {dx, dy}} (table F.10).
     */
    private static final int[][][] DISPLACEMENT = {
            {{0, 1}, {1, 1}, {0, 0}, {1, 0}},
            {{1, 1}, {0, 1}, {1, 0}, {0, 0}}
    };
    /**
     * The component by CFA pattern type and displacement parity, {@code [type][dx][dy]} (table F.11).
     */
    private static final int[][][] COMPONENT = {
            {{2, 0}, {3, 1}},
            {{3, 1}, {2, 0}}
    };

    /**
     * The position in its 2x2 quad of the CFA of each component after the inverse Star-Tetrix transform, by CFA
     * pattern type, {@code [type][component] = {x, y}}: the displacements of table F.10 permuted as the transform
     * permutes the components.
     */
    private static final int[][][] MOSAIC = {
            {{0, 0}, {1, 0}, {0, 1}, {1, 1}},
            {{1, 0}, {0, 0}, {1, 1}, {0, 1}}
    };

    private JxsTransforms() {
        throw new AssertionError();
    }

    /**
     * Inverts the reversible color transform on the first three planes.
     *
     * @param planes the planes, at least three of {@code count} samples
     * @param count the number of samples of each plane
     */
    public static void inverseRct(int[][] planes, int count) {
        var p0 = planes[0];
        var p1 = planes[1];
        var p2 = planes[2];
        for (var i = 0; i < count; i++) {
            var i0 = p0[i];
            var i1 = p1[i];
            var i2 = p2[i];
            var g = i0 - ((i1 + i2) >> 2);
            p0[i] = g + i2;
            p1[i] = g;
            p2[i] = g + i1;
        }
    }

    /**
     * Applies the reversible color transform to the first three planes.
     *
     * @param planes the planes, at least three of {@code count} samples
     * @param count the number of samples of each plane
     */
    public static void forwardRct(int[][] planes, int count) {
        var p0 = planes[0];
        var p1 = planes[1];
        var p2 = planes[2];
        for (var i = 0; i < count; i++) {
            var r = p0[i];
            var g = p1[i];
            var b = p2[i];
            var i1 = b - g;
            var i2 = r - g;
            p0[i] = g + ((i1 + i2) >> 2);
            p1[i] = i1;
            p2[i] = i2;
        }
    }

    /**
     * Returns the CFA pattern type of a Star-Tetrix codestream from its component registration (table F.9): 0 when
     * the second component is displaced horizontally (RGGB, BGGR), 1 otherwise (GRBG, GBRG).
     *
     * @param header the main header
     * @return the pattern type
     * @throws MediaException.Malformed if the registration matches no CFA pattern
     */
    public static int cfaType(JxsHeader header) {
        var x = header.registrationX;
        var y = header.registrationY;
        if (x[1] == 32768 && y[1] == 0 && x[2] == 0 && y[2] == 32768 && (x[0] == y[0]) && (x[3] == y[3])
                && (x[0] == 0 || x[0] == 32768) && (x[3] == 0 || x[3] == 32768) && x[0] != x[3]) {
            return 0;
        }
        if (x[1] == 0 && y[1] == 0 && x[2] == 32768 && y[2] == 32768 && x[0] != y[0] && x[3] != y[3]
                && (x[0] == 0 || x[0] == 32768) && (y[0] == 0 || y[0] == 32768) && x[0] != x[3]) {
            return 1;
        }
        throw new MediaException.Malformed("the JPEG XS component registration matches no Bayer pattern");
    }

    /**
     * {@return the horizontal position in its 2x2 CFA quad of component {@code c} after the inverse Star-Tetrix
     * transform of pattern type {@code type}}
     *
     * @param type the CFA pattern type
     * @param c the component, from 0 to 3
     */
    public static int mosaicX(int type, int c) {
        return MOSAIC[type][c][0];
    }

    /**
     * {@return the vertical position in its 2x2 CFA quad of component {@code c} after the inverse Star-Tetrix
     * transform of pattern type {@code type}}
     *
     * @param type the CFA pattern type
     * @param c the component, from 0 to 3
     */
    public static int mosaicY(int type, int c) {
        return MOSAIC[type][c][1];
    }

    private static int access(int[][] planes, int c, int x, int y, int w, int h, int rx, int ry, int cf, int type) {
        var dx = DISPLACEMENT[type][c][0];
        var dy = DISPLACEMENT[type][c][1];
        if (2 * x + rx + dx < 0 || 2 * x + rx + dx >= 2 * w) {
            rx = -rx;
        }
        if ((cf == 3 && (ry + dy < 0 || ry + dy > 1)) || 2 * y + ry + dy < 0 || 2 * y + ry + dy >= 2 * h) {
            ry = -ry;
        }
        var sx = (2 * x + rx + dx) / 2;
        var sy = (2 * y + ry + dy) / 2;
        var index = COMPONENT[type][(2 + rx + dx) % 2][(2 + ry + dy) % 2];
        return planes[index][sy * w + sx];
    }

    /**
     * Inverts the Star-Tetrix transform (table F.4) of four {@code w x h} planes and puts them in CFA component
     * order.
     *
     * @param planes the four planes, permuted in place
     * @param w the plane width
     * @param h the plane height
     * @param cf the extent {@code Cf}
     * @param type the CFA pattern type
     * @param e1 the exponent of the first chroma component
     * @param e2 the exponent of the second chroma component
     */
    public static void inverseStarTetrix(int[][] planes, int w, int h, int cf, int type, int e1, int e2) {
        var p0 = planes[0];
        var p3 = planes[3];
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var dlt = access(planes, 0, x, y, w, h, -1, -1, cf, type);
                var drt = access(planes, 0, x, y, w, h, 1, -1, cf, type);
                var dlb = access(planes, 0, x, y, w, h, -1, 1, cf, type);
                var drb = access(planes, 0, x, y, w, h, 1, 1, cf, type);
                p0[y * w + x] -= (dlt + drt + dlb + drb) >> 3;
            }
        }
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var ylt = access(planes, 3, x, y, w, h, -1, -1, cf, type);
                var yrt = access(planes, 3, x, y, w, h, 1, -1, cf, type);
                var ylb = access(planes, 3, x, y, w, h, -1, 1, cf, type);
                var yrb = access(planes, 3, x, y, w, h, 1, 1, cf, type);
                p3[y * w + x] += (ylt + yrt + ylb + yrb) >> 2;
            }
        }
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var bl = access(planes, 0, x, y, w, h, -1, 0, cf, type);
                var br = access(planes, 0, x, y, w, h, 1, 0, cf, type);
                var rt = access(planes, 0, x, y, w, h, 0, -1, cf, type);
                var rb = access(planes, 0, x, y, w, h, 0, 1, cf, type);
                p0[y * w + x] -= ((bl + br) << e2) + ((rt + rb) << e1) >> 3;
                var bt = access(planes, 3, x, y, w, h, 0, -1, cf, type);
                var bb = access(planes, 3, x, y, w, h, 0, 1, cf, type);
                var rl = access(planes, 3, x, y, w, h, -1, 0, cf, type);
                var rr = access(planes, 3, x, y, w, h, 1, 0, cf, type);
                p3[y * w + x] -= ((bt + bb) << e2) + ((rl + rr) << e1) >> 3;
            }
        }
        var p1 = planes[1];
        var p2 = planes[2];
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var gl = access(planes, 1, x, y, w, h, -1, 0, cf, type);
                var gr = access(planes, 1, x, y, w, h, 1, 0, cf, type);
                var gt = access(planes, 1, x, y, w, h, 0, -1, cf, type);
                var gb = access(planes, 1, x, y, w, h, 0, 1, cf, type);
                p1[y * w + x] += (gl + gr + gt + gb) >> 2;
                gl = access(planes, 2, x, y, w, h, -1, 0, cf, type);
                gr = access(planes, 2, x, y, w, h, 1, 0, cf, type);
                gt = access(planes, 2, x, y, w, h, 0, -1, cf, type);
                gb = access(planes, 2, x, y, w, h, 0, 1, cf, type);
                p2[y * w + x] += (gl + gr + gt + gb) >> 2;
            }
        }
        planes[0] = p2;
        planes[2] = p0;
        planes[1] = p3;
        planes[3] = p1;
    }

    /**
     * Applies the Star-Tetrix transform to four {@code w x h} planes in CFA component order (the order
     * {@link #inverseStarTetrix} produces) and puts them in codestream component order.
     *
     * @param planes the four planes, permuted in place
     * @param w the plane width
     * @param h the plane height
     * @param cf the extent {@code Cf}
     * @param type the CFA pattern type
     * @param e1 the exponent of the first chroma component
     * @param e2 the exponent of the second chroma component
     */
    public static void forwardStarTetrix(int[][] planes, int w, int h, int cf, int type, int e1, int e2) {
        var p0 = planes[2];
        var p1 = planes[3];
        var p2 = planes[0];
        var p3 = planes[1];
        planes[0] = p0;
        planes[1] = p1;
        planes[2] = p2;
        planes[3] = p3;
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var gl = access(planes, 1, x, y, w, h, -1, 0, cf, type);
                var gr = access(planes, 1, x, y, w, h, 1, 0, cf, type);
                var gt = access(planes, 1, x, y, w, h, 0, -1, cf, type);
                var gb = access(planes, 1, x, y, w, h, 0, 1, cf, type);
                p1[y * w + x] -= (gl + gr + gt + gb) >> 2;
                gl = access(planes, 2, x, y, w, h, -1, 0, cf, type);
                gr = access(planes, 2, x, y, w, h, 1, 0, cf, type);
                gt = access(planes, 2, x, y, w, h, 0, -1, cf, type);
                gb = access(planes, 2, x, y, w, h, 0, 1, cf, type);
                p2[y * w + x] -= (gl + gr + gt + gb) >> 2;
            }
        }
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var bl = access(planes, 0, x, y, w, h, -1, 0, cf, type);
                var br = access(planes, 0, x, y, w, h, 1, 0, cf, type);
                var rt = access(planes, 0, x, y, w, h, 0, -1, cf, type);
                var rb = access(planes, 0, x, y, w, h, 0, 1, cf, type);
                p0[y * w + x] += ((bl + br) << e2) + ((rt + rb) << e1) >> 3;
                var bt = access(planes, 3, x, y, w, h, 0, -1, cf, type);
                var bb = access(planes, 3, x, y, w, h, 0, 1, cf, type);
                var rl = access(planes, 3, x, y, w, h, -1, 0, cf, type);
                var rr = access(planes, 3, x, y, w, h, 1, 0, cf, type);
                p3[y * w + x] += ((bt + bb) << e2) + ((rl + rr) << e1) >> 3;
            }
        }
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var ylt = access(planes, 3, x, y, w, h, -1, -1, cf, type);
                var yrt = access(planes, 3, x, y, w, h, 1, -1, cf, type);
                var ylb = access(planes, 3, x, y, w, h, -1, 1, cf, type);
                var yrb = access(planes, 3, x, y, w, h, 1, 1, cf, type);
                p3[y * w + x] -= (ylt + yrt + ylb + yrb) >> 2;
            }
        }
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var dlt = access(planes, 0, x, y, w, h, -1, -1, cf, type);
                var drt = access(planes, 0, x, y, w, h, 1, -1, cf, type);
                var dlb = access(planes, 0, x, y, w, h, -1, 1, cf, type);
                var drb = access(planes, 0, x, y, w, h, 1, 1, cf, type);
                p0[y * w + x] += (dlt + drt + dlb + drb) >> 3;
            }
        }
    }

    /**
     * Applies the input scaling of the linear nonlinearity: samples of {@code precision} bits to signed values of
     * {@code bw} bits centered on zero.
     *
     * @param samples the samples, unsigned 16-bit values
     * @param count the number of samples
     * @param precision the sample precision, at most {@code bw}
     * @param bw the wavelet precision
     * @param out the scaled values
     */
    public static void input(short[] samples, int count, int precision, int bw, int[] out) {
        var shift = bw - precision;
        var offset = 1 << (bw - 1);
        var mask = (1 << precision) - 1;
        for (var i = 0; i < count; i++) {
            out[i] = ((samples[i] & mask) << shift) - offset;
        }
    }

    /**
     * Scales the reconstructed values of one component to its sample precision through the nonlinearity of the
     * header, clamping to {@code [0, 2^precision - 1]}.
     *
     * @param plane the values of the component
     * @param count the number of values
     * @param header the main header
     * @param precision the bit precision of the component
     * @param out the samples, as unsigned 16-bit values
     */
    public static void output(int[] plane, int count, JxsHeader header, int precision, short[] out) {
        var bw = header.waveletPrecision;
        var max = (1 << precision) - 1;
        switch (header.nonlinearity) {
            case 0 -> {
                var shift = bw - precision;
                var offset = 1L << bw >> 1;
                var round = shift > 0 ? 1L << shift >> 1 : 0;
                for (var i = 0; i < count; i++) {
                    long v = plane[i];
                    v += offset;
                    v = shift >= 0 ? (v + round) >> shift : v << -shift;
                    out[i] = (short) (v < 0 ? 0 : v > max ? max : v);
                }
            }
            case 1 -> {
                var shift = 2 * bw - precision;
                var clamp = (1L << bw) - 1;
                var offset = 1L << bw >> 1;
                var round = 1L << shift >> 1;
                var dco = header.quadraticOffset;
                for (var i = 0; i < count; i++) {
                    long v = plane[i] + offset;
                    v = v < 0 ? 0 : v > clamp ? clamp : v;
                    v = ((v * v + round) >> shift) + dco;
                    out[i] = (short) (v < 0 ? 0 : v > max ? max : v);
                }
            }
            default -> {
                var e = header.extendedE;
                var t1 = header.extendedT1;
                var t2 = header.extendedT2;
                var b1 = t1 + (1L << (bw - e - 1));
                var b2 = t1 * t1;
                var b3 = t2 - (1L << (bw - e - 1));
                var a1 = b2 + (t1 << (bw - e)) + (1L << (2 * bw - 2 - 2 * e));
                var a3 = b2 + (t2 << (bw - e)) - (1L << (2 * bw - 2 - 2 * e));
                var eps = bw - e;
                var shift = 2 * bw - precision;
                var clamp = (1L << bw) - 1;
                var offset = 1L << bw >> 1;
                var round = 1L << shift >> 1;
                for (var i = 0; i < count; i++) {
                    long v = plane[i] + offset;
                    if (v < t1) {
                        v = b1 - v;
                        v = v < 0 ? 0 : v > clamp ? clamp : v;
                        v = a1 - v * v;
                    } else if (v < t2) {
                        v = (v << eps) + b2;
                    } else {
                        v -= b3;
                        v = v < 0 ? 0 : v > clamp ? clamp : v;
                        v = a3 + v * v;
                    }
                    v = (v + round) >> shift;
                    out[i] = (short) (v < 0 ? 0 : v > max ? max : v);
                }
            }
        }
    }
}
