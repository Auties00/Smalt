/* Ports OpenEXR 3.3.5 (ImfRgbaYca.cpp, ImfRgbaFile.cpp,
   ImfChromaticities.cpp). Copyright Contributors to the OpenEXR Project;
   BSD-3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.image.exr.internal;

/**
 * Converts a luminance and chroma image ({@code Y}, {@code RY}, {@code BY}, chroma sampled every other pixel and row)
 * to RGB as OpenEXR's RGBA interface does: chroma rebuilt by a 27-tap filter horizontally on the rows that carry it
 * and vertically on the others, RGB derived through the luminance weights of the primaries, and super-saturated
 * pixels pulled back towards their neighbors' saturation. Every intermediate value is a half, as there.
 */
public final class ExrYca {
    private static final int N = 27;

    private static final int N2 = N / 2;

    private static final float[] TAPS = {0.002128f, -0.007540f, 0.019597f, -0.043159f, 0.087929f, -0.186077f,
            0.627123f, 0.627123f, -0.186077f, 0.087929f, -0.043159f, 0.019597f, -0.007540f, 0.002128f};

    private ExrYca() {
        throw new AssertionError();
    }

    /**
     * Rounds a float to the nearest half.
     */
    private static float half(float value) {
        return Float.float16ToFloat(Float.floatToFloat16(value));
    }

    /**
     * Computes the luminance weights of the primaries: the Y row of their RGB to XYZ matrix, normalized.
     *
     * @param chromaticities red, green, blue and white x and y
     * @return the weights of red, green and blue
     */
    public static float[] weights(float[] chromaticities) {
        var rx = chromaticities[0];
        var ry = chromaticities[1];
        var gx = chromaticities[2];
        var gy = chromaticities[3];
        var bx = chromaticities[4];
        var by = chromaticities[5];
        var wx = chromaticities[6];
        var wy = chromaticities[7];
        var y = 1f;
        var x = wx * y / wy;
        var z = (1 - wx - wy) * y / wy;
        var d = rx * (by - gy) + bx * (gy - ry) + gx * (ry - by);
        var srN = (x * (by - gy) - gx * (y * (by - 1) + by * (x + z)) + bx * (y * (gy - 1) + gy * (x + z)));
        var sgN = (x * (ry - by) + rx * (y * (by - 1) + by * (x + z)) - bx * (y * (ry - 1) + ry * (x + z)));
        var sbN = (x * (gy - ry) - rx * (y * (gy - 1) + gy * (x + z)) + gx * (y * (ry - 1) + ry * (x + z)));
        var sr = srN / d;
        var sg = sgN / d;
        var sb = sbN / d;
        var m01 = sr * ry;
        var m11 = sg * gy;
        var m21 = sb * by;
        var sum = m01 + m11 + m21;
        return new float[]{m01 / sum, m11 / sum, m21 / sum};
    }

    /**
     * Converts the planes.
     *
     * @param width the data window width
     * @param height the data window height
     * @param xMin the left column of the data window
     * @param yMin the top row of the data window
     * @param luminance the {@code Y} samples, one per pixel
     * @param redChroma the {@code RY} samples, one per two by two pixels
     * @param blueChroma the {@code BY} samples, one per two by two pixels
     * @param chromaWidth the samples per row of the chroma planes
     * @param alpha the {@code A} samples, or {@code null}
     * @param weights the luminance weights
     * @return the red, green, blue and alpha planes
     */
    public static float[][] toRgb(int width, int height, int xMin, int yMin, float[] luminance, float[] redChroma,
                                  float[] blueChroma, int chromaWidth, float[] alpha, float[] weights) {
        var yMax = yMin + height - 1;
        var rows = new float[height][];
        for (var row = 0; row < height; row++) {
            rows[row] = line(row, width, xMin, yMin, luminance, redChroma, blueChroma, chromaWidth, alpha);
        }
        var out = new float[4][width * height];
        var rgb = new float[3][];
        for (var scan = yMin; scan <= yMax; scan++) {
            for (var i = 0; i < 3; i++) {
                var line = scan - 1 + i;
                if (((scan + i) & 1) != 0) {
                    rgb[i] = toRgba(rows[clamp(line, yMin, yMax) - yMin], weights);
                } else {
                    var mixed = vertical(rows, line, yMin, yMax, width);
                    rgb[i] = toRgba(mixed, weights);
                }
            }
            saturation(rgb, width, weights, out, (scan - yMin) * width);
        }
        return out;
    }

    /**
     * {@return the row OpenEXR reads for a line, which clamps above the top to the top and below the bottom to the
     * row before the bottom (the top for an image of one row)}
     */
    private static int clamp(int line, int yMin, int yMax) {
        if (line < yMin) {
            return yMin;
        }
        return line > yMax ? Math.max(yMin, yMax - 1) : line;
    }

    /**
     * Reads one row as OpenEXR's luminance and chroma reader keeps it: halves of red chroma, luminance, blue chroma
     * and alpha, the chroma of a row that carries it rebuilt horizontally.
     */
    private static float[] line(int row, int width, int xMin, int yMin, float[] luminance, float[] redChroma,
                                float[] blueChroma, int chromaWidth, float[] alpha) {
        var y = yMin + row;
        var chromaRow = (y & 1) == 0 ? row : row - 1;
        var padded = new float[(width + 2 * N2) * 4];
        for (var x = 0; x < width; x++) {
            var at = (N2 + x) * 4;
            padded[at + 1] = half(luminance[row * width + x]);
            padded[at + 3] = alpha != null ? half(alpha[row * width + x]) : 1f;
            if (((xMin + x) & 1) == 0 && chromaRow >= 0) {
                var chroma = Math.floorDiv(chromaRow + yMin, 2) - Math.floorDiv(yMin + 1, 2);
                var column = Math.floorDiv(xMin + x, 2) - Math.floorDiv(xMin + 1, 2);
                var index = chroma * chromaWidth + column;
                if (index >= 0 && index < redChroma.length) {
                    padded[at] = half(redChroma[index]);
                    padded[at + 2] = half(blueChroma[index]);
                }
            }
        }
        var result = new float[width * 4];
        if ((y & 1) != 0) {
            System.arraycopy(padded, N2 * 4, result, 0, width * 4);
            return result;
        }
        for (var pad = 0; pad < N2; pad++) {
            System.arraycopy(padded, N2 * 4, padded, pad * 4, 4);
            System.arraycopy(padded, (width + N2 - 2) * 4, padded, (width + N2 + pad) * 4, 4);
        }
        for (var j = 0; j < width; j++) {
            var i = j + N2;
            var at = j * 4;
            if ((j & 1) != 0) {
                var r = 0f;
                var b = 0f;
                for (var tap = 0; tap < 14; tap++) {
                    var source = (i - 13 + 2 * tap) * 4;
                    r += padded[source] * TAPS[tap];
                    b += padded[source + 2] * TAPS[tap];
                }
                result[at] = half(r);
                result[at + 2] = half(b);
            } else {
                result[at] = padded[i * 4];
                result[at + 2] = padded[i * 4 + 2];
            }
            result[at + 1] = padded[i * 4 + 1];
            result[at + 3] = padded[i * 4 + 3];
        }
        return result;
    }

    /**
     * Rebuilds the chroma of a row without it from the 14 rows with it around it.
     */
    private static float[] vertical(float[][] rows, int line, int yMin, int yMax, int width) {
        var result = new float[width * 4];
        var center = rows[clamp(line, yMin, yMax) - yMin];
        var sources = new float[14][];
        for (var tap = 0; tap < 14; tap++) {
            sources[tap] = rows[clamp(line - 13 + 2 * tap, yMin, yMax) - yMin];
        }
        for (var x = 0; x < width; x++) {
            var r = 0f;
            var b = 0f;
            for (var tap = 0; tap < 14; tap++) {
                r += sources[tap][x * 4] * TAPS[tap];
                b += sources[tap][x * 4 + 2] * TAPS[tap];
            }
            result[x * 4] = half(r);
            result[x * 4 + 1] = center[x * 4 + 1];
            result[x * 4 + 2] = half(b);
            result[x * 4 + 3] = center[x * 4 + 3];
        }
        return result;
    }

    /**
     * Derives RGB from luminance and chroma.
     */
    private static float[] toRgba(float[] yca, float[] weights) {
        var out = new float[yca.length];
        for (var at = 0; at < yca.length; at += 4) {
            var luminance = yca[at + 1];
            if (yca[at] == 0 && yca[at + 2] == 0) {
                out[at] = luminance;
                out[at + 1] = luminance;
                out[at + 2] = luminance;
            } else {
                var r = (yca[at] + 1) * luminance;
                var b = (yca[at + 2] + 1) * luminance;
                var g = (luminance - r * weights[0] - b * weights[2]) / weights[1];
                out[at] = half(r);
                out[at + 1] = half(g);
                out[at + 2] = half(b);
            }
            out[at + 3] = yca[at + 3];
        }
        return out;
    }

    /**
     * {@return the saturation of a pixel: 1 minus its smallest component over its largest}
     */
    private static float saturation(float[] pixels, int at) {
        var max = Math.max(pixels[at], Math.max(pixels[at + 1], pixels[at + 2]));
        var min = Math.min(pixels[at], Math.min(pixels[at + 1], pixels[at + 2]));
        return max > 0 ? 1 - min / max : 0;
    }

    /**
     * Pulls super-saturated pixels of the middle row back towards the saturation of their diagonal neighbors and
     * writes the row.
     */
    private static void saturation(float[][] rows, int width, float[] weights, float[][] out, int offset) {
        var above = rows[0];
        var middle = rows[1];
        var below = rows[2];
        var a2 = saturation(above, 0);
        var a1 = a2;
        var b2 = saturation(below, 0);
        var b1 = b2;
        for (var i = 0; i < width; i++) {
            var a0 = a1;
            a1 = a2;
            var b0 = b1;
            b1 = b2;
            if (i < width - 1) {
                a2 = saturation(above, (i + 1) * 4);
                b2 = saturation(below, (i + 1) * 4);
            }
            var mean = Math.min(1.0f, 0.25f * (a0 + a2 + b0 + b2));
            var at = i * 4;
            var s = saturation(middle, at);
            var r = middle[at];
            var g = middle[at + 1];
            var b = middle[at + 2];
            if (s > mean) {
                var limit = Math.min(1.0f, 1 - (1 - mean) * 0.25f);
                if (s > limit) {
                    var f = limit / s;
                    var max = Math.max(r, Math.max(g, b));
                    var outR = half(Math.max(max - (max - r) * f, 0.0f));
                    var outG = half(Math.max(max - (max - g) * f, 0.0f));
                    var outB = half(Math.max(max - (max - b) * f, 0.0f));
                    var yIn = r * weights[0] + g * weights[1] + b * weights[2];
                    var yOut = outR * weights[0] + outG * weights[1] + outB * weights[2];
                    if (yOut > 0) {
                        outR = half(outR * (yIn / yOut));
                        outG = half(outG * (yIn / yOut));
                        outB = half(outB * (yIn / yOut));
                    }
                    r = outR;
                    g = outG;
                    b = outB;
                }
            }
            out[0][offset + i] = r;
            out[1][offset + i] = g;
            out[2][offset + i] = b;
            out[3][offset + i] = middle[at + 3];
        }
    }
}
