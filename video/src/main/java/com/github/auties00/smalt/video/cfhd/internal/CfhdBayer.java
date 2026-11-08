/* Ports cineform-sdk (Codec/bayer.c GenerateBYR2, DemosaicRAW and FastSharpeningBlurV, Codec/DemoasicFrames.cpp
 * ColorDifference2Bayer, DebayerLine, DoDEBAYER_ORDER_RED_GRN, DoDEBAYER_ORDER_GRN_RED, DoDEBAYER_ORDER_GRN_BLU,
 * DoDEBAYER_ORDER_BLU_GRN, REDCELL, GRNREDCELL, GRNBLUCELL, BLUCELL and FastSharpeningBlurHinplace, Codec/decoder.c
 * the BYR4LinearRestore table, Common/AVIExtendedHeader.h the curve functions: CineForm RAW reconstruction).
 * Copyright GoPro Inc; Apache License 2.0 or MIT. Modified for Smalt. */
package com.github.auties00.smalt.video.cfhd.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Turns the four channels of a CineForm RAW frame into a color filter array mosaic or into demosaiced RGB.
 *
 * <p>A Bayer sample codes each 2x2 quad of photosites {@code r, g1, g2, b} as four channels at half the frame size:
 * {@code G = (g1 + g2) / 2}, {@code RG = (r - G) / 2 + mid}, {@code BG = (b - G) / 2 + mid} and
 * {@code GD = (g1 - g2) / 2 + mid}, where {@code g1} shares the row of red. The methods take the reconstructed channels
 * as 16-bit samples (the coded precision shifted to the top bits), in that channel order.
 *
 * <p>The mosaic follows GoPro's 16-bit Bayer output: the four photosites are rebuilt at full precision, saturated to
 * 16 bits and mapped through the inverse of the encoder's curve, or kept with their lowest bit cleared. The demosaic
 * follows GoPro's default 16-bit RGB output: the photosites are rebuilt with the 14-bit arithmetic of its vector loop
 * over the largest multiple of 8 quads of a row (and at full precision past it), interpolated with its adaptive 5x5
 * filters away from the borders and bilinearly next to them, then sharpened horizontally and vertically with the
 * (-1, 4, 10, 4, -1) kernel. The rows of a demosaic are spread over the module's shared helper threads; the result
 * does not depend on how. Instances hold their buffers and are not thread-safe.
 */
public final class CfhdBayer {
    private static final int CURVE_TYPE_LOG = 1;
    private static final int CURVE_TYPE_GAMMA = 2;
    private static final int CURVE_TYPE_LINEAR = 4;
    private static final int CURVE_TYPE_CINEON = 5;
    private static final int CURVE_TYPE_PARA = 6;
    private static final int CURVE_TYPE_CINE985 = 7;
    private static final int CURVE_TYPE_CSTYLE = 8;
    private static final int CURVE_TYPE_SLOG = 9;
    private static final int CURVE_TYPE_LOGC = 10;
    private static final int CURVE_TYPE_EXTENDED = 0x4000;

    /**
     * The points of the curve modelled on Technicolor CineStyle: pairs of a coded and a linear value.
     */
    private static final float[] CSTYLE = {
            0.000f, 0.000f, 0.050f, 0.001f, 0.100f, 0.002f, 0.150f, 0.004f, 0.200f, 0.010f, 0.251f, 0.022f,
            0.302f, 0.040f, 0.349f, 0.070f, 0.400f, 0.110f, 0.451f, 0.160f, 0.502f, 0.240f, 0.557f, 0.340f,
            0.698f, 0.657f, 0.741f, 0.751f, 0.804f, 0.852f, 0.839f, 0.900f, 0.886f, 0.940f, 0.916f, 0.960f,
            0.950f, 0.980f, 0.975f, 0.990f, 1.000f, 1.000f};

    /**
     * The photosites of the frame being demosaiced, row after row.
     */
    private int[] bayer = new int[0];

    /**
     * The horizontally interpolated and sharpened R, G and B samples of the frame being demosaiced.
     */
    private int[] rgb = new int[0];

    /**
     * Constructs the buffers.
     */
    public CfhdBayer() {
    }

    /**
     * {@return whether {@link #restoreTable(int)} undoes an encode curve: false for the linear curve and for curve
     * types this class does not know}
     *
     * @param encodeCurve the {@code ECRV} metadata value, 0 for CineForm's default log curve of base 90
     */
    public static boolean linearizes(int encodeCurve) {
        var type = encodeCurve >>> 16;
        if (type == 0) {
            return true;
        }
        return switch (type & 0xFF) {
            case CURVE_TYPE_LOG, CURVE_TYPE_GAMMA, CURVE_TYPE_CINEON, CURVE_TYPE_CINE985, CURVE_TYPE_PARA,
                 CURVE_TYPE_CSTYLE, CURVE_TYPE_SLOG, CURVE_TYPE_LOGC -> true;
            default -> false;
        };
    }

    /**
     * {@return whether an encode curve is the linear one, whose coded photosites are already linear}
     *
     * @param encodeCurve the {@code ECRV} metadata value
     */
    public static boolean linear(int encodeCurve) {
        return (encodeCurve >>> 16 & 0xFF) == CURVE_TYPE_LINEAR;
    }

    /**
     * Builds the table that maps a coded photosite, a 16-bit sample shifted down to 14 bits, to its linear 16-bit
     * value, as GoPro's decoder builds it in single precision.
     *
     * <p>The curve is {@code 0xTTTTBBCC}: a type {@code T} with a base of {@code B / C}, or, with
     * {@code 0x4000} in the type, of the 16 bits {@code BBCC}.
     *
     * @param encodeCurve the {@code ECRV} metadata value, 0 for CineForm's default log curve of base 90; it must
     * {@link #linearizes(int) linearize}
     * @return the 16384 linear values
     * @throws IllegalArgumentException if the curve does not linearize
     */
    public static int[] restoreTable(int encodeCurve) {
        var type = encodeCurve >>> 16;
        float base;
        if (type != 0) {
            base = (type & CURVE_TYPE_EXTENDED) != 0 ? (float) (encodeCurve & 0xFFFF)
                    : (float) (encodeCurve >> 8 & 0xFF) / (float) (encodeCurve & 0xFF);
        } else {
            type = CURVE_TYPE_LOG;
            base = 90.0f;
        }
        var gain = encodeCurve >> 8 & 0xFF;
        var power = encodeCurve & 0xFF;
        var table = new int[16384];
        for (var j = 0; j < table.length; j++) {
            var i = (float) j / 16384.0f;
            var linear = switch (type & 0xFF) {
                case CURVE_TYPE_LOG -> (float) ((StrictMath.pow(base, i) - 1.0) / (base - 1.0));
                case CURVE_TYPE_GAMMA -> (float) StrictMath.pow(i, base);
                case CURVE_TYPE_CINEON -> cineonToLinear(i, base);
                case CURVE_TYPE_CINE985 -> (float) StrictMath.pow(10.0,
                        (i - 985.0 / 1023.0) * 1023.0 * (base / 1.7) * 0.002 / 0.6);
                case CURVE_TYPE_PARA -> (float) (1.0 - (float) StrictMath.pow(1.0 - (double) i,
                        1.0 / ((double) power * 256.0))) * (float) gain;
                case CURVE_TYPE_CSTYLE -> cstyleToLinear(i);
                case CURVE_TYPE_SLOG -> (float) (StrictMath.pow(10.0, (i - 0.616596 - 0.03) / 0.432699) - 0.037584);
                case CURVE_TYPE_LOGC -> i > 0.1496582
                        ? (float) (StrictMath.pow(10.0, (i - 0.385537) / 0.2471896) * 0.18 - 0.00937677)
                        : (float) ((i / 0.9661776 - 0.04378604) * 0.18 - 0.00937677);
                default -> throw new IllegalArgumentException("curve " + Integer.toHexString(encodeCurve));
            };
            var scaled = linear * 65535.0f;
            // a value with no int representation converts to the smallest int, as the x86 conversion does
            var value = scaled >= -2147483648.0f && scaled < 2147483648.0f ? (int) scaled : Integer.MIN_VALUE;
            table[j] = Math.clamp(value, 0, 65535);
        }
        return table;
    }

    private static float cineonToLinear(float i, float p) {
        var black = StrictMath.pow(10.0, (95.0 / 1023.0 - 685.0 / 1023.0) * 1023.0 * (p / 1.7) * 0.002 / 0.6);
        double value = Math.max(i, 0.0f);
        return (float) (StrictMath.pow(10.0, (value - 685.0 / 1023.0) * 1023.0 * (p / 1.7) * 0.002 / 0.6) - black);
    }

    private static float cstyleToLinear(float i) {
        var pos = 0;
        while ((CSTYLE[pos * 2] > i || i > CSTYLE[pos * 2 + 2]) && pos < 19) {
            pos++;
        }
        var mix = (i - CSTYLE[pos * 2]) / (CSTYLE[pos * 2 + 2] - CSTYLE[pos * 2]);
        return (CSTYLE[pos * 2 + 3] - CSTYLE[pos * 2 + 1]) * mix + CSTYLE[pos * 2 + 1];
    }

    private static int saturate(int value) {
        return Math.clamp(value, 0, 65535);
    }

    /**
     * Rebuilds the color filter array mosaic of a frame.
     *
     * @param planes the 16-bit G, RG, BG and GD channels
     * @param stride the distance between the rows of a channel
     * @param quadsWide the number of quads of a row, half the frame width
     * @param quadsHigh the number of quad rows, half the frame height
     * @param phase the color filter array arrangement, 0 to 3 for {@code RGGB}, {@code GRBG}, {@code GBRG} and
     * {@code BGGR}
     * @param restore the table of {@link #restoreTable(int)}, or {@code null} to keep the coded photosites, their
     * lowest bit cleared as GoPro's decoder clears it
     * @param out receives the {@code 2 * quadsWide} by {@code 2 * quadsHigh} photosites
     * @param outStride the distance between the rows of {@code out}
     */
    public static void mosaic(int[][] planes, int stride, int quadsWide, int quadsHigh, int phase, int[] restore,
            short[] out, int outStride) {
        var gPlane = planes[0];
        var rgPlane = planes[1];
        var bgPlane = planes[2];
        var gdPlane = planes[3];
        for (var y = 0; y < quadsHigh; y++) {
            var top = 2 * y * outStride;
            var bottom = top + outStride;
            for (var x = 0; x < quadsWide; x++) {
                var at = y * stride + x;
                var g = gPlane[at];
                var gd = gdPlane[at] - 32768;
                var r = saturate(((rgPlane[at] - 32768) << 1) + g);
                var b = saturate(((bgPlane[at] - 32768) << 1) + g);
                var g1 = saturate(g + gd);
                var g2 = saturate(g - gd);
                if (restore != null) {
                    r = restore[r >> 2];
                    g1 = restore[g1 >> 2];
                    g2 = restore[g2 >> 2];
                    b = restore[b >> 2];
                } else {
                    r &= 0xFFFE;
                    g1 &= 0xFFFE;
                    g2 &= 0xFFFE;
                    b &= 0xFFFE;
                }
                place(out, top + 2 * x, bottom + 2 * x, phase, r, g1, g2, b);
            }
        }
    }

    private static void place(short[] out, int top, int bottom, int phase, int r, int g1, int g2, int b) {
        switch (phase) {
            case 0 -> {
                out[top] = (short) r;
                out[top + 1] = (short) g1;
                out[bottom] = (short) g2;
                out[bottom + 1] = (short) b;
            }
            case 1 -> {
                out[top] = (short) g1;
                out[top + 1] = (short) r;
                out[bottom] = (short) b;
                out[bottom + 1] = (short) g2;
            }
            case 2 -> {
                out[top] = (short) g1;
                out[top + 1] = (short) b;
                out[bottom] = (short) r;
                out[bottom + 1] = (short) g2;
            }
            default -> {
                out[top] = (short) b;
                out[top + 1] = (short) g1;
                out[bottom] = (short) g2;
                out[bottom + 1] = (short) r;
            }
        }
    }

    /**
     * Demosaics a frame into 16-bit RGB.
     *
     * @param planes the 16-bit G, RG, BG and GD channels
     * @param stride the distance between the rows of a channel
     * @param quadsWide the number of quads of a row, half the frame width, at least 2
     * @param quadsHigh the number of quad rows, half the frame height, at least 1
     * @param phase the color filter array arrangement, 0 to 3 for {@code RGGB}, {@code GRBG}, {@code GBRG} and
     * {@code BGGR}
     * @param limits the bounds the buffers are checked against when they grow
     * @param threads the most threads the rows are spread over, or 0 for as many as the shared helpers allow
     * @param out receives the {@code 2 * quadsWide} by {@code 2 * quadsHigh} pixels as R, G, B and A samples, alpha
     * opaque
     * @param outStride the distance between the rows of {@code out}, in samples
     */
    public void demosaic(int[][] planes, int stride, int quadsWide, int quadsHigh, int phase, MediaLimits limits,
            int threads, short[] out, int outStride) {
        var width = 2 * quadsWide;
        var height = 2 * quadsHigh;
        var samples = width * height;
        if (bayer.length < samples) {
            LimitChecks.checkAllocation(limits, 16L * samples);
            bayer = new int[samples];
            rgb = new int[3 * samples];
        }
        // each pass reads only what the pass before it wrote, so the rows of a pass run in parallel bands
        var split = ParallelBands.bands(quadsHigh, 12L * samples);
        var bands = threads > 0 ? Math.min(split, threads) : split;
        ParallelBands.run(bands, band -> {
            var to = ParallelBands.start(band + 1, bands, quadsHigh);
            for (var y = ParallelBands.start(band, bands, quadsHigh); y < to; y++) {
                colorDifferenceToBayer(planes, y * stride, quadsWide, phase, bayer, 2 * y * width, width);
            }
        });
        ParallelBands.run(bands, band -> {
            var to = ParallelBands.start(band + 1, bands, quadsHigh);
            for (var y = ParallelBands.start(band, bands, quadsHigh); y < to; y++) {
                var at = 2 * y * width * 3;
                switch (phase) {
                    case 0 -> redGreen(width, height, 2 * y, at);
                    case 1 -> greenRed(width, height, 2 * y, at);
                    case 2 -> greenBlue(width, height, 2 * y, at);
                    default -> blueGreen(width, height, 2 * y, at);
                }
                sharpenRow(rgb, at, width);
                sharpenRow(rgb, at + 3 * width, width);
            }
        });
        var pitch = 3 * width;
        ParallelBands.run(bands, band -> {
            var to = ParallelBands.start(band + 1, bands, quadsHigh);
            for (var y = ParallelBands.start(band, bands, quadsHigh); y < to; y++) {
                var low = y >= 1;
                var high = y < quadsHigh - 1;
                for (var k = 0; k < 2; k++) {
                    var c = 2 * y + k;
                    var a = (low ? 2 * y - 2 : 2 * y) + k;
                    var b = (low ? 2 * y - 1 : 2 * y) + k;
                    var d = (high ? 2 * y + 1 : 2 * y) + k;
                    var e = (high ? 2 * y + 2 : 2 * y) + k;
                    sharpenColumn(rgb, a * pitch, b * pitch, c * pitch, d * pitch, e * pitch, pitch, out,
                            c * outStride);
                }
            }
        });
    }

    /**
     * Rebuilds the two photosite rows of one row of quads, with the 14-bit arithmetic of GoPro's vector loop over its
     * first {@code quadsWide & 0xFFF8} quads.
     */
    private static void colorDifferenceToBayer(int[][] planes, int from, int quadsWide, int phase, int[] out,
            int top, int width) {
        var vector = quadsWide & 0xFFF8;
        var bottom = top + width;
        for (var x = 0; x < quadsWide; x++) {
            var at = from + x;
            int r;
            int g1;
            int g2;
            int b;
            if (x < vector) {
                var g = planes[0][at] >>> 2;
                var rg = planes[1][at] >>> 2;
                var bg = planes[2][at] >>> 2;
                var gd = planes[3][at] >>> 2;
                gd -= 8192;
                r = Math.clamp(((rg - 8192) << 1) + g, 0, 16383) << 2;
                b = Math.clamp(((bg - 8192) << 1) + g, 0, 16383) << 2;
                g1 = Math.clamp(g + gd, 0, 16383) << 2;
                g2 = Math.clamp(g - gd, 0, 16383) << 2;
            } else {
                var g = planes[0][at];
                var gd = planes[3][at] - 32768;
                r = saturate(((planes[1][at] - 32768) << 1) + g);
                b = saturate(((planes[2][at] - 32768) << 1) + g);
                g1 = saturate(g + gd);
                g2 = saturate(g - gd);
            }
            var t = top + 2 * x;
            var u = bottom + 2 * x;
            switch (phase) {
                case 0 -> {
                    out[t] = r;
                    out[t + 1] = g1;
                    out[u] = g2;
                    out[u + 1] = b;
                }
                case 1 -> {
                    out[t] = g1;
                    out[t + 1] = r;
                    out[u] = b;
                    out[u + 1] = g2;
                }
                case 2 -> {
                    out[t] = g1;
                    out[t + 1] = b;
                    out[u] = r;
                    out[u + 1] = g2;
                }
                default -> {
                    out[t] = b;
                    out[t + 1] = g1;
                    out[u] = g2;
                    out[u + 1] = r;
                }
            }
        }
    }

    /**
     * Sharpens one row of RGB triplets in place with the (-1, 4, 10, 4, -1) / 16 kernel, blurring the second and the
     * second to last pixels with (1, 2, 1) / 4 and keeping the first and the last.
     */
    private static void sharpenRow(int[] row, int at, int width) {
        var r0 = row[at];
        var g0 = row[at + 1];
        var b0 = row[at + 2];
        var r1 = (row[at] + row[at + 3] * 2 + row[at + 6]) >> 2;
        var g1 = (row[at + 1] + row[at + 4] * 2 + row[at + 7]) >> 2;
        var b1 = (row[at + 2] + row[at + 5] * 2 + row[at + 8]) >> 2;
        // the results are written two pixels behind the samples they are computed from, then moved into place
        var write = at;
        for (var i = 2; i < width - 2; i++) {
            for (var c = 0; c < 3; c++) {
                var s = at + 3 * i + c;
                row[write++] = saturate((-row[s - 6] + row[s - 3] * 4 + row[s] * 10 + row[s + 3] * 4 - row[s + 6]) >> 4);
            }
        }
        var s = at + 3 * (width - 2);
        for (var c = 0; c < 3; c++) {
            row[write++] = (row[s + c - 3] + row[s + c] * 2 + row[s + c + 3]) >> 2;
        }
        for (var c = 0; c < 3; c++) {
            row[write++] = row[s + 3 + c];
        }
        System.arraycopy(row, at, row, at + 6, 3 * (width - 2));
        row[at] = r0;
        row[at + 1] = g0;
        row[at + 2] = b0;
        row[at + 3] = r1;
        row[at + 4] = g1;
        row[at + 5] = b1;
    }

    /**
     * Sharpens one output row vertically with GoPro's 16-bit vector arithmetic for the (-1, 4, 10, 4, -1) kernel and
     * writes it as opaque RGBA.
     */
    private static void sharpenColumn(int[] rgb, int a, int b, int c, int d, int e, int count, short[] out,
            int outAt) {
        for (int i = 0, to = outAt; i < count; i += 3, to += 4) {
            for (var k = 0; k < 3; k++) {
                var mix = (rgb[c + i + k] >>> 4) * 5;
                mix = Math.max(mix - ((rgb[a + i + k] >>> 4) >> 1), 0);
                mix = Math.max(mix - ((rgb[e + i + k] >>> 4) >> 1), 0);
                mix = Math.min(mix + (rgb[b + i + k] >>> 4) * 2, 65535);
                mix = Math.min(mix + (rgb[d + i + k] >>> 4) * 2, 65535);
                mix = Math.min(mix, 0x7FFF) << 1;
                out[to + k] = (short) mix;
            }
            out[to + 3] = (short) 0xFFFF;
        }
    }

    private static int avg2(int a, int b) {
        return (a + b + 1) >> 1;
    }

    private static int avg4(int a, int b, int c, int d) {
        return (a + b + c + d + 2) >> 2;
    }

    private void put(int t, int r, int g, int b) {
        rgb[t] = r;
        rgb[t + 1] = g;
        rgb[t + 2] = b;
    }

    /**
     * Interpolates a red photosite with the adaptive 5x5 filter.
     */
    private void redCell(int t, int p, int w) {
        var s = bayer;
        var diffR = Math.abs(s[p - 2] - s[p + 2]) >> 10;
        var diffG = Math.abs(s[p - 1] - s[p + 1]) >> 10;
        var diffB = Math.abs(s[p - w - 1] - s[p + w + 1]) >> 10;
        var factorR = 2 + 2 * diffR * diffR / (2 + diffG * diffG);
        var factorB = 4 + 4 * diffG * diffG / (2 + diffB * diffB);
        var g = (-s[p - 2 * w] + s[p - w] * factorR - s[p - 2] + s[p - 1] * factorR + s[p] * 4 + s[p + 1] * factorR
                - s[p + 2] + s[p + w] * factorR - s[p + 2 * w]) / (4 * factorR);
        var b = (s[p - 2 * w] * -3 + s[p - w - 1] * factorB + s[p - w + 1] * factorB + s[p - 2] * -3 + s[p] * 12
                + s[p + 2] * -3 + s[p + w - 1] * factorB + s[p + w + 1] * factorB + s[p + 2 * w] * -3) / (4 * factorB);
        put(t, s[p], saturate(g), saturate(b));
    }

    /**
     * Interpolates a green photosite of a red row with the adaptive 5x5 filter.
     */
    private void greenRedCell(int t, int p, int w) {
        var s = bayer;
        var diffR = Math.abs(s[p - 1] - s[p + 1]) >> 10;
        var diffG = Math.abs(s[p - 2] - s[p + 2]) >> 10;
        var diffB = Math.abs(s[p - w] - s[p + w]) >> 10;
        var factorR = 8 + 4 * diffG * diffG / (2 + diffR * diffR);
        var factorB = 8 + 4 * diffG * diffG / (2 + diffB * diffB);
        var r = (s[p - 2 * w] + s[p - w - 1] * -2 + s[p - w + 1] * -2 + s[p - 2] * -2 + s[p - 1] * factorR
                + s[p] * 10 + s[p + 1] * factorR + s[p + 2] * -2 + s[p + w - 1] * -2 + s[p + w + 1] * -2
                + s[p + 2 * w]) / (factorR * 2);
        var b = (s[p - 2 * w] * -2 + s[p - w - 1] * -2 + s[p - w] * factorB + s[p - w + 1] * -2 + s[p - 2]
                + s[p] * 10 + s[p + 2] + s[p + w - 1] * -2 + s[p + w] * factorB + s[p + w + 1] * -2
                + s[p + 2 * w] * -2) / (factorB * 2);
        put(t, saturate(r), s[p], saturate(b));
    }

    /**
     * Interpolates a green photosite of a blue row with the adaptive 5x5 filter.
     */
    private void greenBlueCell(int t, int p, int w) {
        var s = bayer;
        var diffR = Math.abs(s[p - w] - s[p + w]) >> 10;
        var diffG = Math.abs(s[p - 2 * w] - s[p + 2 * w]) >> 10;
        var diffB = Math.abs(s[p - 1] - s[p + 1]) >> 10;
        var factorR = 8 + 4 * diffG * diffG / (2 + diffR * diffR);
        var factorB = 8 + 4 * diffG * diffG / (2 + diffB * diffB);
        var r = (s[p - 2 * w] * -2 + s[p - w - 1] * -2 + s[p - w] * factorR + s[p - w + 1] * -2 + s[p - 2]
                + s[p] * 10 + s[p + 2] + s[p + w - 1] * -2 + s[p + w] * factorR + s[p + w + 1] * -2
                + s[p + 2 * w] * -2) / (factorR * 2);
        var b = (s[p - 2 * w] + s[p - w - 1] * -2 + s[p - w + 1] * -2 + s[p - 2] * -2 + s[p - 1] * factorB
                + s[p] * 10 + s[p + 1] * factorB + s[p + 2] * -2 + s[p + w - 1] * -2 + s[p + w + 1] * -2
                + s[p + 2 * w]) / (factorB * 2);
        put(t, saturate(r), s[p], saturate(b));
    }

    /**
     * Interpolates a blue photosite with the adaptive 5x5 filter.
     */
    private void blueCell(int t, int p, int w) {
        var s = bayer;
        var diffR = Math.abs(s[p - w - 1] - s[p + w + 1]) >> 10;
        var diffG = Math.abs(s[p - 1] - s[p + 1]) >> 10;
        var diffB = Math.abs(s[p - 2] - s[p + 2]) >> 10;
        var factorR = 4 + 4 * diffG * diffG / (2 + diffR * diffR);
        var factorB = 2 + 2 * diffB * diffB / (2 + diffG * diffG);
        var r = (s[p - 2 * w] * -3 + s[p - w - 1] * factorR + s[p - w + 1] * factorR + s[p - 2] * -3 + s[p] * 12
                + s[p + 2] * -3 + s[p + w - 1] * factorR + s[p + w + 1] * factorR + s[p + 2 * w] * -3) / (factorR * 4);
        var g = (-s[p - 2 * w] + s[p - w] * factorB - s[p - 2] + s[p - 1] * factorB + s[p] * 4 + s[p + 1] * factorB
                - s[p + 2] + s[p + w] * factorB - s[p + 2 * w]) / (factorB * 4);
        put(t, saturate(r), saturate(g), s[p]);
    }

    /**
     * Interpolates two rows of an {@code RGGB} mosaic starting at an even row.
     */
    private void redGreen(int w, int h, int row, int t) {
        var s = bayer;
        var o = row * w;
        var odd = row + 1;
        if (row > 0 && row < h - 2) {
            put(t, s[o], avg2(s[o - w], s[o + w]), avg2(s[o - w + 1], s[o + w + 1]));
            o++;
            t += 3;
            put(t, avg2(s[o - 1], s[o + 1]), s[o], avg2(s[o - w], s[o + w]));
            o++;
            t += 3;
            for (var x = 2; x < w - 2; x += 2) {
                redCell(t, o, w);
                o++;
                t += 3;
                greenRedCell(t, o, w);
                o++;
                t += 3;
            }
            put(t, s[o], avg4(s[o - 1], s[o + 1], s[o - w], s[o + w]),
                    avg4(s[o - w - 1], s[o - w + 1], s[o + w - 1], s[o + w + 1]));
            o++;
            t += 3;
            put(t, s[o - 1], s[o], avg2(s[o - w], s[o + w]));
            o++;
            t += 3;
        } else if (row > 0) {
            put(t, s[o], avg2(s[o - w], s[o + w]), avg2(s[o - w + 1], s[o + w + 1]));
            o++;
            t += 3;
            for (var x = 1; x < w - 1; x += 2) {
                put(t, avg2(s[o - 1], s[o + 1]), s[o], avg2(s[o - w], s[o + w]));
                o++;
                t += 3;
                put(t, s[o], avg4(s[o - 1], s[o + 1], s[o - w], s[o + w]),
                        avg4(s[o - w - 1], s[o - w + 1], s[o + w - 1], s[o + w + 1]));
                o++;
                t += 3;
            }
            put(t, s[o - 1], s[o], avg2(s[o - w], s[o + w]));
            o++;
            t += 3;
        } else {
            put(t, s[o], avg2(s[o + 1], s[o + w]), s[o + w + 1]);
            o++;
            t += 3;
            for (var x = 1; x < w - 1; x += 2) {
                put(t, avg2(s[o - 1], s[o + 1]), s[o], s[o + w]);
                o++;
                t += 3;
                put(t, s[o], avg2(s[o - 1], s[o + 1]), avg2(s[o + w - 1], s[o + w + 1]));
                o++;
                t += 3;
            }
            put(t, s[o - 1], s[o], s[o + w]);
            o++;
            t += 3;
        }
        if (odd > 1 && odd < h - 1) {
            put(t, avg2(s[o - w], s[o + w]), s[o], s[o + 1]);
            o++;
            t += 3;
            put(t, avg4(s[o - w - 1], s[o - w + 1], s[o + w - 1], s[o + w + 1]),
                    avg4(s[o - 1], s[o + 1], s[o - w], s[o + w]), s[o]);
            o++;
            t += 3;
            for (var x = 2; x < w - 2; x += 2) {
                greenBlueCell(t, o, w);
                o++;
                t += 3;
                blueCell(t, o, w);
                o++;
                t += 3;
            }
            put(t, avg2(s[o - w], s[o + w]), s[o], avg2(s[o - 1], s[o + 1]));
            o++;
            t += 3;
            put(t, avg2(s[o - w - 1], s[o + w - 1]), avg2(s[o - w], s[o + w]), s[o]);
        } else if (odd < h - 1) {
            put(t, avg2(s[o - w], s[o + w]), s[o], s[o + 1]);
            o++;
            t += 3;
            for (var x = 1; x < w - 1; x += 2) {
                put(t, avg4(s[o - w - 1], s[o - w + 1], s[o + w - 1], s[o + w + 1]),
                        avg4(s[o - 1], s[o + 1], s[o - w], s[o + w]), s[o]);
                o++;
                t += 3;
                put(t, avg2(s[o - w], s[o + w]), s[o], avg2(s[o - 1], s[o + 1]));
                o++;
                t += 3;
            }
            put(t, avg2(s[o - w - 1], s[o + w - 1]), avg2(s[o - w], s[o + w]), s[o]);
        } else {
            put(t, s[o - w], s[o], s[o + 1]);
            o++;
            t += 3;
            for (var x = 1; x < w - 1; x += 2) {
                put(t, avg2(s[o - w - 1], s[o - w + 1]), avg2(s[o - 1], s[o + 1]), s[o]);
                o++;
                t += 3;
                put(t, s[o - w], s[o], avg2(s[o - 1], s[o + 1]));
                o++;
                t += 3;
            }
            put(t, s[o - w - 1], s[o - 1], s[o]);
        }
    }

    /**
     * Interpolates two rows of a {@code GBRG} mosaic starting at an even row.
     */
    private void greenBlue(int w, int h, int row, int t) {
        var s = bayer;
        var o = row * w;
        var odd = row + 1;
        if (row > 0 && row < h - 2) {
            put(t, avg2(s[o - w], s[o + w]), s[o], s[o + 1]);
            o++;
            t += 3;
            put(t, avg4(s[o - w - 1], s[o - w + 1], s[o + w - 1], s[o + w + 1]),
                    avg4(s[o - 1], s[o + 1], s[o - w], s[o + w]), s[o]);
            o++;
            t += 3;
            for (var x = 2; x < w - 2; x += 2) {
                greenBlueCell(t, o, w);
                o++;
                t += 3;
                blueCell(t, o, w);
                o++;
                t += 3;
            }
            // GoPro's decoder takes red from the horizontal and blue from the vertical neighbors here
            put(t, avg2(s[o - 1], s[o + 1]), s[o], avg2(s[o - w], s[o + w]));
            o++;
            t += 3;
            put(t, avg2(s[o - w - 1], s[o + w - 1]), avg2(s[o - w], s[o + w]), s[o]);
            o++;
            t += 3;
        } else if (row > 0) {
            put(t, avg2(s[o - w], s[o + w]), s[o], s[o + 1]);
            o++;
            t += 3;
            for (var x = 1; x < w - 1; x += 2) {
                put(t, avg4(s[o - w - 1], s[o - w + 1], s[o + w - 1], s[o + w + 1]),
                        avg4(s[o - 1], s[o + 1], s[o - w], s[o + w]), s[o]);
                o++;
                t += 3;
                put(t, avg2(s[o - w], s[o + w]), s[o], avg2(s[o - 1], s[o + 1]));
                o++;
                t += 3;
            }
            put(t, avg2(s[o - w - 1], s[o + w - 1]), avg2(s[o - w], s[o + w]), s[o]);
            o++;
            t += 3;
        } else {
            put(t, s[o + w], s[o], s[o + 1]);
            o++;
            t += 3;
            for (var x = 1; x < w - 1; x += 2) {
                put(t, avg2(s[o + w - 1], s[o + w + 1]), avg2(s[o - 1], s[o + 1]), s[o]);
                o++;
                t += 3;
                put(t, s[o + w], s[o], avg2(s[o - 1], s[o + 1]));
                o++;
                t += 3;
            }
            put(t, s[o + w - 1], s[o - 1], s[o]);
            o++;
            t += 3;
        }
        if (odd > 1 && odd < h - 1) {
            put(t, s[o], avg2(s[o - w], s[o + w]), avg2(s[o - w + 1], s[o + w + 1]));
            o++;
            t += 3;
            put(t, avg2(s[o - 1], s[o + 1]), s[o], avg2(s[o - w], s[o + w]));
            o++;
            t += 3;
            for (var x = 2; x < w - 2; x += 2) {
                redCell(t, o, w);
                o++;
                t += 3;
                greenRedCell(t, o, w);
                o++;
                t += 3;
            }
            put(t, s[o], avg4(s[o - 1], s[o + 1], s[o - w], s[o + w]),
                    avg4(s[o - w - 1], s[o - w + 1], s[o + w - 1], s[o + w + 1]));
            o++;
            t += 3;
            put(t, s[o - 1], s[o], avg2(s[o - w], s[o + w]));
        } else if (odd < h - 1) {
            put(t, s[o], avg2(s[o - w], s[o + w]), avg2(s[o - w + 1], s[o + w + 1]));
            o++;
            t += 3;
            for (var x = 1; x < w - 1; x += 2) {
                put(t, avg2(s[o - 1], s[o + 1]), s[o], avg2(s[o - w], s[o + w]));
                o++;
                t += 3;
                put(t, s[o], avg4(s[o - 1], s[o + 1], s[o - w], s[o + w]),
                        avg4(s[o - w - 1], s[o - w + 1], s[o + w - 1], s[o + w + 1]));
                o++;
                t += 3;
            }
            put(t, s[o - 1], s[o], avg2(s[o - w], s[o + w]));
        } else {
            put(t, s[o], s[o - w], s[o - w + 1]);
            o++;
            t += 3;
            for (var x = 1; x < w - 1; x += 2) {
                put(t, avg2(s[o - 1], s[o + 1]), s[o], s[o - w]);
                o++;
                t += 3;
                put(t, s[o], avg2(s[o - 1], s[o + 1]), avg2(s[o - w - 1], s[o - w + 1]));
                o++;
                t += 3;
            }
            put(t, s[o - 1], s[o], s[o - w]);
        }
    }

    /**
     * Interpolates two rows of a {@code GRBG} mosaic starting at an even row.
     */
    private void greenRed(int w, int h, int row, int t) {
        var s = bayer;
        var o = row * w;
        var odd = row + 1;
        if (row > 0 && row < h - 2) {
            put(t, s[o + 1], s[o], avg2(s[o + w], s[o - w]));
            o++;
            t += 3;
            put(t, s[o], avg4(s[o - 1], s[o + 1], s[o - w], s[o + w]),
                    avg4(s[o - w - 1], s[o - w + 1], s[o + w - 1], s[o + w + 1]));
            o++;
            t += 3;
            for (var x = 2; x < w - 2; x += 2) {
                greenRedCell(t, o, w);
                o++;
                t += 3;
                redCell(t, o, w);
                o++;
                t += 3;
            }
            put(t, avg2(s[o - 1], s[o + 1]), s[o], avg2(s[o - w], s[o + w]));
            o++;
            t += 3;
            put(t, s[o], s[o - 1], avg2(s[o - w - 1], s[o + w - 1]));
            o++;
            t += 3;
        } else if (row > 0) {
            put(t, s[o + 1], s[o], avg2(s[o + w], s[o - w]));
            o++;
            t += 3;
            for (var x = 1; x < w - 1; x += 2) {
                put(t, s[o], avg4(s[o - 1], s[o + 1], s[o - w], s[o + w]),
                        avg4(s[o - w - 1], s[o - w + 1], s[o + w - 1], s[o + w + 1]));
                o++;
                t += 3;
                put(t, avg2(s[o - 1], s[o + 1]), s[o], avg2(s[o - w], s[o + w]));
                o++;
                t += 3;
            }
            put(t, s[o], s[o - 1], avg2(s[o - w - 1], s[o + w - 1]));
            o++;
            t += 3;
        } else {
            put(t, s[o + 1], s[o], s[o + w]);
            o++;
            t += 3;
            for (var x = 1; x < w - 1; x += 2) {
                put(t, s[o], avg2(s[o - 1], s[o + 1]), avg2(s[o + w - 1], s[o + w + 1]));
                o++;
                t += 3;
                put(t, avg2(s[o - 1], s[o + 1]), s[o], s[o + w]);
                o++;
                t += 3;
            }
            put(t, s[o], s[o - 1], s[o + w - 1]);
            o++;
            t += 3;
        }
        if (odd > 1 && odd < h - 1) {
            put(t, avg2(s[o - w + 1], s[o + w + 1]), avg2(s[o - w], s[o + w]), s[o]);
            o++;
            t += 3;
            put(t, avg2(s[o - w], s[o + w]), s[o], avg2(s[o - 1], s[o + 1]));
            o++;
            t += 3;
            for (var x = 2; x < w - 2; x += 2) {
                blueCell(t, o, w);
                o++;
                t += 3;
                greenBlueCell(t, o, w);
                o++;
                t += 3;
            }
            put(t, avg4(s[o - w - 1], s[o - w + 1], s[o + w - 1], s[o + w + 1]),
                    avg4(s[o - 1], s[o + 1], s[o - w], s[o + w]), s[o]);
            o++;
            t += 3;
            put(t, avg2(s[o - w], s[o + w]), s[o], s[o - 1]);
        } else if (odd < h - 1) {
            put(t, avg2(s[o - w + 1], s[o + w + 1]), avg2(s[o - w], s[o + w]), s[o]);
            o++;
            t += 3;
            for (var x = 1; x < w - 1; x += 2) {
                put(t, avg2(s[o - w], s[o + w]), s[o], avg2(s[o - 1], s[o + 1]));
                o++;
                t += 3;
                put(t, avg4(s[o - w - 1], s[o - w + 1], s[o + w - 1], s[o + w + 1]),
                        avg4(s[o - 1], s[o + 1], s[o - w], s[o + w]), s[o]);
                o++;
                t += 3;
            }
            put(t, avg2(s[o - w], s[o + w]), s[o], s[o - 1]);
        } else {
            put(t, s[o - w + 1], avg2(s[o - w], s[o + 1]), s[o]);
            o++;
            t += 3;
            for (var x = 1; x < w - 1; x += 2) {
                put(t, s[o - w], s[o], avg2(s[o - 1], s[o + 1]));
                o++;
                t += 3;
                put(t, avg2(s[o - w - 1], s[o - w + 1]), avg2(s[o - 1], s[o + 1]), s[o]);
                o++;
                t += 3;
            }
            put(t, s[o - w], s[o], s[o - 1]);
        }
    }

    /**
     * Interpolates two rows of a {@code BGGR} mosaic starting at an even row.
     */
    private void blueGreen(int w, int h, int row, int t) {
        var s = bayer;
        var o = row * w;
        var odd = row + 1;
        if (row > 0 && row < h - 2) {
            put(t, avg2(s[o - w + 1], s[o + w + 1]), avg2(s[o - w], s[o + w]), s[o]);
            o++;
            t += 3;
            put(t, avg2(s[o - w], s[o + w]), s[o], avg2(s[o - 1], s[o + 1]));
            o++;
            t += 3;
            for (var x = 2; x < w - 2; x += 2) {
                blueCell(t, o, w);
                o++;
                t += 3;
                greenBlueCell(t, o, w);
                o++;
                t += 3;
            }
            put(t, avg4(s[o - w - 1], s[o - w + 1], s[o + w - 1], s[o + w + 1]),
                    avg4(s[o - 1], s[o + 1], s[o - w], s[o + w]), s[o]);
            o++;
            t += 3;
            put(t, avg2(s[o - w], s[o + w]), s[o], s[o - 1]);
            o++;
            t += 3;
        } else if (row > 0) {
            put(t, avg2(s[o - w + 1], s[o + w + 1]), avg2(s[o - w], s[o + w]), s[o]);
            o++;
            t += 3;
            for (var x = 1; x < w - 1; x += 2) {
                put(t, avg2(s[o - w], s[o + w]), s[o], avg2(s[o - 1], s[o + 1]));
                o++;
                t += 3;
                put(t, avg4(s[o - w - 1], s[o - w + 1], s[o + w - 1], s[o + w + 1]),
                        avg4(s[o - 1], s[o + 1], s[o - w], s[o + w]), s[o]);
                o++;
                t += 3;
            }
            put(t, avg2(s[o - w], s[o + w]), s[o], s[o - 1]);
            o++;
            t += 3;
        } else {
            put(t, s[o + w + 1], avg2(s[o + 1], s[o + w]), s[o]);
            o++;
            t += 3;
            for (var x = 1; x < w - 1; x += 2) {
                put(t, s[o + w], s[o], avg2(s[o - 1], s[o + 1]));
                o++;
                t += 3;
                put(t, avg2(s[o + w - 1], s[o + w + 1]), avg2(s[o - 1], s[o + 1]), s[o]);
                o++;
                t += 3;
            }
            put(t, s[o + w], s[o], s[o - 1]);
            o++;
            t += 3;
        }
        if (odd > 1 && odd < h - 1) {
            put(t, s[o + 1], s[o], avg2(s[o - w], s[o + w]));
            o++;
            t += 3;
            put(t, s[o], avg4(s[o - 1], s[o + 1], s[o - w], s[o + w]),
                    avg4(s[o - w - 1], s[o - w + 1], s[o + w - 1], s[o + w + 1]));
            o++;
            t += 3;
            for (var x = 2; x < w - 2; x += 2) {
                greenRedCell(t, o, w);
                o++;
                t += 3;
                redCell(t, o, w);
                o++;
                t += 3;
            }
            put(t, avg2(s[o - 1], s[o + 1]), s[o], avg2(s[o - w], s[o + w]));
            o++;
            t += 3;
            put(t, s[o], avg2(s[o - w], s[o + w]), avg2(s[o - w - 1], s[o + w - 1]));
        } else if (odd < h - 1) {
            put(t, s[o + 1], s[o], avg2(s[o - w], s[o + w]));
            o++;
            t += 3;
            for (var x = 1; x < w - 1; x += 2) {
                put(t, s[o], avg4(s[o - 1], s[o + 1], s[o - w], s[o + w]),
                        avg4(s[o - w - 1], s[o - w + 1], s[o + w - 1], s[o + w + 1]));
                o++;
                t += 3;
                put(t, avg2(s[o - 1], s[o + 1]), s[o], avg2(s[o - w], s[o + w]));
                o++;
                t += 3;
            }
            put(t, s[o], avg2(s[o - w], s[o + w]), avg2(s[o - w - 1], s[o + w - 1]));
        } else {
            put(t, s[o + 1], s[o], s[o - w]);
            o++;
            t += 3;
            for (var x = 1; x < w - 1; x += 2) {
                put(t, s[o], avg2(s[o - 1], s[o + 1]), avg2(s[o - w - 1], s[o - w + 1]));
                o++;
                t += 3;
                put(t, avg2(s[o - 1], s[o + 1]), s[o], s[o - w]);
                o++;
                t += 3;
            }
            put(t, s[o], s[o - 1], s[o - w - 1]);
        }
    }
}
