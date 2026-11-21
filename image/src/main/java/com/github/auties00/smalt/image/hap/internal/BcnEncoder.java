package com.github.auties00.smalt.image.hap.internal;

/**
 * Encodes 4 by 4 texel blocks into the BCn compression formats HAP stores: BC1 color, the interpolated
 * single-channel block of BC3 alpha and BC4, scaled YCoCg in BC3, BC7 mode 6 and BC6H mode 11.
 *
 * <p>Endpoints follow the principal axis of the block's colors, refined against the axis-aligned bounding box, and
 * every index is the nearest entry of the palette the decoder rebuilds from the quantized endpoints, so a block
 * of one color decodes to the color its endpoints quantize to. Texels are packed as {@code 0xAABBGGRR}, row-major.
 * Arguments are not checked; values outside the documented ranges give unspecified results. Instances hold their
 * working arrays and are not thread-safe.
 */
public final class BcnEncoder {
    /**
     * The 4-bit interpolation weights of BC6H and BC7, in 1/64.
     */
    private static final int[] WEIGHTS4 = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};

    /**
     * The interpolation weights of the eight-value mode of BC4, in 1/65536.
     */
    private static final int[] SMOOTH_WEIGHTS6 = {9363, 18724, 28086, 37450, 46812, 56173};

    /**
     * The interpolation weights of the six-value mode of BC4, in 1/65536.
     */
    private static final int[] SMOOTH_WEIGHTS4 = {13107, 26215, 39321, 52429};

    /**
     * The distances from 0 and 255 within which the six-value mode tries to code a value as the explicit 0 or 255.
     */
    private static final int[] EXPLICIT_MARGINS = {0, 8, 24, 64};

    private final int[] red = new int[4];

    private final int[] green = new int[4];

    private final int[] blue = new int[4];

    private final int[] values = new int[16];

    private final int[] indices = new int[16];

    /**
     * The scaled YCoCg texels of a block.
     */
    private final int[] scaled = new int[16];

    private final int[] channel = new int[16];

    /**
     * The index bits of the single-channel block {@link #indexSingle(int[])} last picked.
     */
    private long singleIndices;

    private final double[] axis = new double[4];

    private final double[] mean = new double[4];

    private final double[] low = new double[4];

    private final double[] high = new double[4];

    private final int[] palette = new int[64];

    private final int[] levels = new int[48];

    private final double[] covariance = new double[16];

    private final double[] next = new double[4];

    private final long[] bits = new long[2];

    /**
     * Constructs an encoder with its working arrays.
     */
    public BcnEncoder() {
    }

    /**
     * Encodes the color of a block as a BC1 block in its four-color mode; alpha is ignored.
     *
     * @param texels the 16 texels
     * @param out the destination
     * @param offset the index of the block's first byte; 8 bytes are written
     */
    public void color(int[] texels, byte[] out, int offset) {
        var minB = 255;
        var maxB = 0;
        for (var texel : texels) {
            var b = texel >>> 16 & 0xFF;
            minB = Math.min(minB, b);
            maxB = Math.max(maxB, b);
        }
        endpoints(texels, 3);
        var best = Long.MAX_VALUE;
        var bestBlock0 = 0;
        var bestBlock1 = 0;
        for (var candidate = 0; candidate < 2; candidate++) {
            double r0;
            double g0;
            double b0;
            double r1;
            double g1;
            double b1;
            if (candidate == 0) {
                r0 = low[0];
                g0 = low[1];
                b0 = low[2];
                r1 = high[0];
                g1 = high[1];
                b1 = high[2];
            } else {
                var minR = 255;
                var maxR = 0;
                var minG = 255;
                var maxG = 0;
                for (var texel : texels) {
                    minR = Math.min(minR, texel & 0xFF);
                    maxR = Math.max(maxR, texel & 0xFF);
                    minG = Math.min(minG, texel >>> 8 & 0xFF);
                    maxG = Math.max(maxG, texel >>> 8 & 0xFF);
                }
                var rising = axis[0] * axis[1] >= 0;
                r0 = minR;
                r1 = maxR;
                g0 = rising ? minG : maxG;
                g1 = rising ? maxG : minG;
                var risingB = axis[0] * axis[2] >= 0;
                b0 = risingB ? minB : maxB;
                b1 = risingB ? maxB : minB;
            }
            if (minB == maxB) {
                b0 = minB;
                b1 = minB;
            }
            var c0 = rgb565(r0, g0, b0);
            var c1 = rgb565(r1, g1, b1);
            if (c0 < c1) {
                var swap = c0;
                c0 = c1;
                c1 = swap;
            }
            var error = colorIndices(texels, c0, c1);
            if (error < best) {
                best = error;
                bestBlock0 = c0 | c1 << 16;
                var packed = 0;
                for (var texel = 0; texel < 16; texel++) {
                    packed |= indices[texel] << (2 * texel);
                }
                bestBlock1 = packed;
            }
        }
        putInt(out, offset, bestBlock0);
        putInt(out, offset + 4, bestBlock1);
    }

    /**
     * Quantizes a color to 5-6-5 bits.
     *
     * @param r the red value, from 0 to 255
     * @param g the green value, from 0 to 255
     * @param b the blue value, from 0 to 255
     * @return the packed 16-bit color
     */
    private static int rgb565(double r, double g, double b) {
        var r5 = (int) Math.round(clamp(r) * 31 / 255);
        var g6 = (int) Math.round(clamp(g) * 63 / 255);
        var b5 = (int) Math.round(clamp(b) * 31 / 255);
        return r5 << 11 | g6 << 5 | b5;
    }

    /**
     * Chooses the nearest palette entry of every texel of a BC1 block in the four-color mode.
     *
     * @param texels the 16 texels of the block
     * @param c0 the first endpoint
     * @param c1 the second endpoint
     * @return the total squared error
     */
    private long colorIndices(int[] texels, int c0, int c1) {
        var r0 = c0 >>> 11 & 0x1F;
        var g0 = c0 >>> 5 & 0x3F;
        var b0 = c0 & 0x1F;
        var r1 = c1 >>> 11 & 0x1F;
        var g1 = c1 >>> 5 & 0x3F;
        var b1 = c1 & 0x1F;
        red[0] = (r0 * 527 + 23) >>> 6;
        green[0] = (g0 * 259 + 33) >>> 6;
        blue[0] = (b0 * 527 + 23) >>> 6;
        red[1] = (r1 * 527 + 23) >>> 6;
        green[1] = (g1 * 259 + 33) >>> 6;
        blue[1] = (b1 * 527 + 23) >>> 6;
        red[2] = ((2 * r0 + r1) * 351 + 61) >>> 7;
        green[2] = ((2 * g0 + g1) * 2763 + 1039) >>> 11;
        blue[2] = ((2 * b0 + b1) * 351 + 61) >>> 7;
        red[3] = ((r0 + r1 * 2) * 351 + 61) >>> 7;
        green[3] = ((g0 + g1 * 2) * 2763 + 1039) >>> 11;
        blue[3] = ((b0 + b1 * 2) * 351 + 61) >>> 7;
        var total = 0L;
        for (var texel = 0; texel < 16; texel++) {
            var r = texels[texel] & 0xFF;
            var g = texels[texel] >>> 8 & 0xFF;
            var b = texels[texel] >>> 16 & 0xFF;
            var bestIndex = 0;
            var bestError = Integer.MAX_VALUE;
            for (var index = 0; index < 4; index++) {
                var dr = r - red[index];
                var dg = g - green[index];
                var db = b - blue[index];
                var error = dr * dr + dg * dg + db * db;
                if (error < bestError) {
                    bestError = error;
                    bestIndex = index;
                }
            }
            indices[texel] = bestIndex;
            total += bestError;
        }
        return total;
    }

    /**
     * Encodes 16 values from 0 to 255 as an interpolated single-channel block.
     *
     * <p>The block takes the mode of lower squared error: the eight-value mode between the smallest and the largest
     * value, refined by least squares, or the six-value mode, whose explicit 0 and 255 code the values near the ends
     * of the range and whose four interpolated values span the others.
     *
     * @param samples the 16 values
     * @param precise whether the intermediate values are rounded, as BC4 has them, rather than truncated, as BC3 alpha
     * has them
     * @param out the destination
     * @param offset the index of the block's first byte; 8 bytes are written
     */
    public void single(int[] samples, boolean precise, byte[] out, int offset) {
        var min = 255;
        var max = 0;
        for (var sample : samples) {
            min = Math.min(min, sample);
            max = Math.max(max, sample);
        }
        var block = (long) max | (long) min << 8;
        if (max != min) {
            values[0] = max;
            values[1] = min;
            for (var index = 0; index < 6; index++) {
                values[2 + index] = precise
                        ? (SMOOTH_WEIGHTS6[5 - index] * max + SMOOTH_WEIGHTS6[index] * min + 32768) >> 16
                        : ((6 - index) * max + (1 + index) * min) / 7;
            }
            var bestError = indexSingle(samples);
            block |= singleIndices << 16;
            for (var iteration = 0; iteration < 3 && bestError > 0; iteration++) {
                var refined = refineSingle(samples, block >>> 16, precise);
                if (refined == 0) {
                    break;
                }
                var error = indexSingle(samples);
                if (error >= bestError) {
                    break;
                }
                bestError = error;
                block = refined | singleIndices << 16;
            }
            // endpoints beyond the range of the values can put evenly spaced clusters of values on interpolants;
            // a block already within one level on average is not searched
            for (var below = 0; below <= 40 && bestError > 16; below += 8) {
                for (var above = 0; above <= 40; above += 8) {
                    var high = Math.min(255, max + above);
                    var low = Math.max(0, min - below);
                    if (high == max && low == min) {
                        continue;
                    }
                    var error = indexSingle(samples, high, low, precise);
                    if (error < bestError) {
                        bestError = error;
                        block = (long) high | (long) low << 8 | singleIndices << 16;
                    }
                }
            }
            for (var margin : EXPLICIT_MARGINS) {
                if (bestError == 0) {
                    break;
                }
                var low = 255;
                var high = 0;
                for (var sample : samples) {
                    if (sample > margin && sample < 255 - margin) {
                        low = Math.min(low, sample);
                        high = Math.max(high, sample);
                    }
                }
                if (low > high) {
                    low = 0;
                    high = 0;
                }
                values[0] = low;
                values[1] = high;
                for (var index = 0; index < 4; index++) {
                    values[2 + index] = precise
                            ? (SMOOTH_WEIGHTS4[3 - index] * low + SMOOTH_WEIGHTS4[index] * high + 32768) >> 16
                            : ((4 - index) * low + (1 + index) * high) / 5;
                }
                values[6] = 0;
                values[7] = 255;
                var error = indexSingle(samples);
                if (error < bestError) {
                    bestError = error;
                    block = (long) low | (long) high << 8 | singleIndices << 16;
                }
            }
        }
        for (var i = 0; i < 8; i++) {
            out[offset + i] = (byte) (block >>> (8 * i));
        }
    }

    /**
     * Loads the eight values of an eight-value single-channel block into {@link #values} and picks for each of 16
     * values the nearest of them.
     *
     * @param samples the 16 values
     * @param first the first endpoint, larger than {@code second}
     * @param second the second endpoint
     * @param precise whether the intermediate values are rounded, as BC4 has them, rather than truncated
     * @return the sum of the squared differences between the values and the picked ones; the index bits are left in
     * {@link #singleIndices}
     */
    private long indexSingle(int[] samples, int first, int second, boolean precise) {
        values[0] = first;
        values[1] = second;
        for (var index = 0; index < 6; index++) {
            values[2 + index] = precise
                    ? (SMOOTH_WEIGHTS6[5 - index] * first + SMOOTH_WEIGHTS6[index] * second + 32768) >> 16
                    : ((6 - index) * first + (1 + index) * second) / 7;
        }
        return indexSingle(samples);
    }

    /**
     * Fits the endpoints of an eight-value single-channel block to 16 values by least squares, keeping each value's
     * interpolation index, and loads the eight values of the fitted endpoints into {@link #values}.
     *
     * @param samples the 16 values
     * @param indices the 48 index bits of the current block
     * @param precise whether the intermediate values are rounded, as BC4 has them, rather than truncated
     * @return the endpoint bytes of the fitted block (the first endpoint in the low byte), or 0 when the fit does not
     * give two distinct endpoints
     */
    private long refineSingle(int[] samples, long indices, boolean precise) {
        double a = 0;
        double b = 0;
        double c = 0;
        double x = 0;
        double y = 0;
        for (var texel = 0; texel < 16; texel++) {
            var index = (int) (indices >>> (3 * texel) & 7);
            // the weight of the second endpoint: index 0 is the first, 1 the second, 2 to 7 lie between
            var weight = index == 0 ? 0 : index == 1 ? 1 : (index - 1) / 7.0;
            var sample = samples[texel];
            a += (1 - weight) * (1 - weight);
            b += (1 - weight) * weight;
            c += weight * weight;
            x += (1 - weight) * sample;
            y += weight * sample;
        }
        var determinant = a * c - b * b;
        if (Math.abs(determinant) < 1e-9) {
            return 0;
        }
        var first = (int) Math.clamp(Math.round((x * c - y * b) / determinant), 0, 255);
        var second = (int) Math.clamp(Math.round((a * y - b * x) / determinant), 0, 255);
        var max = Math.max(first, second);
        var min = Math.min(first, second);
        if (max == min) {
            return 0;
        }
        values[0] = max;
        values[1] = min;
        for (var index = 0; index < 6; index++) {
            values[2 + index] = precise
                    ? (SMOOTH_WEIGHTS6[5 - index] * max + SMOOTH_WEIGHTS6[index] * min + 32768) >> 16
                    : ((6 - index) * max + (1 + index) * min) / 7;
        }
        return (long) max | (long) min << 8;
    }

    /**
     * Picks for each of 16 values the nearest of the eight values of a single-channel block.
     *
     * <p>The 48 index bits are left in {@link #singleIndices}.
     *
     * @param samples the 16 values
     * @return the sum of the squared differences between the values and the picked ones
     */
    private long indexSingle(int[] samples) {
        var total = 0L;
        var bits = 0L;
        for (var texel = 0; texel < 16; texel++) {
            var bestIndex = 0;
            var bestError = Integer.MAX_VALUE;
            for (var index = 0; index < 8; index++) {
                var difference = samples[texel] - values[index];
                var error = difference * difference;
                if (error < bestError) {
                    bestError = error;
                    bestIndex = index;
                }
            }
            bits |= (long) bestIndex << (3 * texel);
            total += bestError;
        }
        singleIndices = bits;
        return total;
    }

    /**
     * Encodes the alpha of a block as the alpha half of a BC3 block.
     *
     * @param texels the 16 texels
     * @param out the destination
     * @param offset the index of the block's first byte; 8 bytes are written
     */
    public void alpha(int[] texels, byte[] out, int offset) {
        for (var texel = 0; texel < 16; texel++) {
            channel[texel] = texels[texel] >>> 24;
        }
        single(channel, false, out, offset);
    }

    /**
     * Encodes a block as a BC3 block of RGBA.
     *
     * @param texels the 16 texels
     * @param out the destination
     * @param offset the index of the block's first byte; 16 bytes are written
     */
    public void rgba(int[] texels, byte[] out, int offset) {
        alpha(texels, out, offset);
        color(texels, out, offset + 8);
    }

    /**
     * Encodes the color of a block as a BC3 block of scaled YCoCg: Co and Cg multiplied by the block's scale in red and
     * green, the scale code in blue and the luma in alpha.
     *
     * @param texels the 16 texels; alpha is ignored
     * @param out the destination
     * @param offset the index of the block's first byte; 16 bytes are written
     */
    public void ycocg(int[] texels, byte[] out, int offset) {
        var largest = 0;
        for (var texel = 0; texel < 16; texel++) {
            var r = texels[texel] & 0xFF;
            var g = texels[texel] >>> 8 & 0xFF;
            var b = texels[texel] >>> 16 & 0xFF;
            var co = Math.floorDiv(r - b + 1, 2);
            var cg = Math.floorDiv(2 * g - r - b + 2, 4);
            largest = Math.max(largest, Math.max(Math.abs(co), Math.abs(cg)));
        }
        var scale = largest < 32 ? 4 : largest < 64 ? 2 : 1;
        for (var texel = 0; texel < 16; texel++) {
            var r = texels[texel] & 0xFF;
            var g = texels[texel] >>> 8 & 0xFF;
            var b = texels[texel] >>> 16 & 0xFF;
            var co = Math.floorDiv(r - b + 1, 2);
            var cg = Math.floorDiv(2 * g - r - b + 2, 4);
            var luma = (r + 2 * g + b + 2) >> 2;
            var storedR = Math.clamp(co * scale + 128, 0, 255);
            var storedG = Math.clamp(cg * scale + 128, 0, 255);
            scaled[texel] = luma << 24 | (scale - 1) << 19 | storedG << 8 | storedR;
        }
        alpha(scaled, out, offset);
        color(scaled, out, offset + 8);
    }

    /**
     * Encodes a block as a BC7 block in mode 6: one subset, 7-bit RGBA endpoints with a p-bit each and 4-bit indices.
     *
     * @param texels the 16 texels
     * @param out the destination
     * @param offset the index of the block's first byte; 16 bytes are written
     */
    public void bc7(int[] texels, byte[] out, int offset) {
        endpoints(texels, 4);
        var p0 = pbit(low);
        var p1 = pbit(high);
        for (var c = 0; c < 4; c++) {
            red[c] = quantize7(low[c], p0);
            green[c] = quantize7(high[c], p1);
        }
        for (var weight = 0; weight < 16; weight++) {
            for (var c = 0; c < 4; c++) {
                var e0 = red[c] << 1 | p0;
                var e1 = green[c] << 1 | p1;
                palette[weight * 4 + c] = ((64 - WEIGHTS4[weight]) * e0 + WEIGHTS4[weight] * e1 + 32) >> 6;
            }
        }
        for (var texel = 0; texel < 16; texel++) {
            var bestIndex = 0;
            var bestError = Integer.MAX_VALUE;
            for (var index = 0; index < 16; index++) {
                var error = 0;
                for (var c = 0; c < 4; c++) {
                    var d = (texels[texel] >>> (8 * c) & 0xFF) - palette[index * 4 + c];
                    error += d * d;
                }
                if (error < bestError) {
                    bestError = error;
                    bestIndex = index;
                }
            }
            indices[texel] = bestIndex;
        }
        if (indices[0] >= 8) {
            for (var c = 0; c < 4; c++) {
                var swap = red[c];
                red[c] = green[c];
                green[c] = swap;
            }
            var swap = p0;
            p0 = p1;
            p1 = swap;
            for (var texel = 0; texel < 16; texel++) {
                indices[texel] = 15 - indices[texel];
            }
        }
        var bits = this.bits;
        bits[0] = 0;
        bits[1] = 0;
        var at = put(bits, 0, 1 << 6, 7);
        for (var c = 0; c < 4; c++) {
            at = put(bits, at, red[c], 7);
            at = put(bits, at, green[c], 7);
        }
        at = put(bits, at, p0, 1);
        at = put(bits, at, p1, 1);
        for (var texel = 0; texel < 16; texel++) {
            at = put(bits, at, indices[texel], texel == 0 ? 3 : 4);
        }
        putLong(out, offset, bits[0]);
        putLong(out, offset + 8, bits[1]);
    }

    /**
     * {@return the p-bit that quantizes an endpoint with the least error}
     *
     * @param endpoint the endpoint's four channels, from 0 to 255
     */
    private static int pbit(double[] endpoint) {
        var best = 0;
        var bestError = Double.MAX_VALUE;
        for (var p = 0; p < 2; p++) {
            var error = 0.0;
            for (var c = 0; c < 4; c++) {
                var d = endpoint[c] - (quantize7(endpoint[c], p) << 1 | p);
                error += d * d;
            }
            if (error < bestError) {
                bestError = error;
                best = p;
            }
        }
        return best;
    }

    /**
     * {@return the 7-bit value that, with a p-bit, comes nearest an 8-bit value}
     *
     * @param value the value, from 0 to 255
     * @param p the p-bit
     */
    private static int quantize7(double value, int p) {
        return (int) Math.clamp(Math.round((clamp(value) - p) / 2), 0, 127);
    }

    /**
     * Encodes a block of non-negative half-precision RGB values as a BC6H block of the unsigned format in mode 11: one
     * region, 10-bit endpoints and 4-bit indices.
     *
     * @param halves the 48 IEEE 754 binary16 values, three per texel; negative values and values above the largest
     * finite one are clamped
     * @param out the destination
     * @param offset the index of the block's first byte; 16 bytes are written
     */
    public void bc6h(short[] halves, byte[] out, int offset) {
        for (var c = 0; c < 3; c++) {
            mean[c] = 0;
        }
        for (var texel = 0; texel < 16; texel++) {
            for (var c = 0; c < 3; c++) {
                var h = halves[texel * 3 + c] & 0xFFFF;
                h = (h & 0x8000) != 0 ? 0 : Math.min(h, 0x7BFF);
                palette[texel * 3 + c] = h;
                mean[c] += h / 16.0;
            }
        }
        principalAxis(palette, 3);
        var minT = Double.MAX_VALUE;
        var maxT = -Double.MAX_VALUE;
        for (var texel = 0; texel < 16; texel++) {
            var t = 0.0;
            for (var c = 0; c < 3; c++) {
                t += (palette[texel * 3 + c] - mean[c]) * axis[c];
            }
            minT = Math.min(minT, t);
            maxT = Math.max(maxT, t);
        }
        var e0 = red;
        var e1 = green;
        for (var c = 0; c < 3; c++) {
            e0[c] = quantize10(mean[c] + minT * axis[c]);
            e1[c] = quantize10(mean[c] + maxT * axis[c]);
        }
        var level = levels;
        for (var weight = 0; weight < 16; weight++) {
            for (var c = 0; c < 3; c++) {
                var a = unquantize10(e0[c]);
                var b = unquantize10(e1[c]);
                level[weight * 3 + c] = (((64 - WEIGHTS4[weight]) * a + WEIGHTS4[weight] * b + 32) >> 6) * 31 >> 6;
            }
        }
        for (var texel = 0; texel < 16; texel++) {
            var bestIndex = 0;
            var bestError = Long.MAX_VALUE;
            for (var index = 0; index < 16; index++) {
                var error = 0L;
                for (var c = 0; c < 3; c++) {
                    long d = palette[texel * 3 + c] - level[index * 3 + c];
                    error += d * d;
                }
                if (error < bestError) {
                    bestError = error;
                    bestIndex = index;
                }
            }
            indices[texel] = bestIndex;
        }
        if (indices[0] >= 8) {
            for (var c = 0; c < 3; c++) {
                var swap = e0[c];
                e0[c] = e1[c];
                e1[c] = swap;
            }
            for (var texel = 0; texel < 16; texel++) {
                indices[texel] = 15 - indices[texel];
            }
        }
        var bits = this.bits;
        bits[0] = 0;
        bits[1] = 0;
        var at = put(bits, 0, 0b00011, 5);
        for (var c = 0; c < 3; c++) {
            at = put(bits, at, e0[c], 10);
        }
        for (var c = 0; c < 3; c++) {
            at = put(bits, at, e1[c], 10);
        }
        for (var texel = 0; texel < 16; texel++) {
            at = put(bits, at, indices[texel], texel == 0 ? 3 : 4);
        }
        putLong(out, offset, bits[0]);
        putLong(out, offset + 8, bits[1]);
    }

    /**
     * {@return the 10-bit endpoint whose unquantized and finished value comes nearest a half-precision value}
     *
     * @param half the value's bits, from 0 to {@code 0x7BFF}
     */
    private static int quantize10(double half) {
        var target = Math.clamp(half, 0, 0x7BFF);
        var guess = (int) Math.clamp(Math.round(target * 64 / 31 * 1024 / 65536 - 0.5), 0, 1023);
        var best = guess;
        var bestError = Double.MAX_VALUE;
        for (var x = Math.max(0, guess - 2); x <= Math.min(1023, guess + 2); x++) {
            var error = Math.abs((unquantize10(x) * 31 >> 6) - target);
            if (error < bestError) {
                bestError = error;
                best = x;
            }
        }
        return best;
    }

    /**
     * {@return a 10-bit unsigned BC6H endpoint expanded to 16 bits}
     *
     * @param value the endpoint
     */
    private static int unquantize10(int value) {
        if (value == 0) {
            return 0;
        }
        if (value == 1023) {
            return 0xFFFF;
        }
        return ((value << 16) + 0x8000) >> 10;
    }

    /**
     * Finds the extremes of a block's texels along their principal axis, into {@link #low} and {@link #high}.
     *
     * @param texels the 16 texels of the block
     * @param channels the channels considered, 3 for RGB or 4 for RGBA
     */
    private void endpoints(int[] texels, int channels) {
        for (var c = 0; c < channels; c++) {
            var sum = 0;
            for (var texel : texels) {
                sum += texel >>> (8 * c) & 0xFF;
            }
            mean[c] = sum / 16.0;
        }
        for (var texel = 0; texel < 16; texel++) {
            for (var c = 0; c < channels; c++) {
                palette[texel * channels + c] = texels[texel] >>> (8 * c) & 0xFF;
            }
        }
        principalAxis(palette, channels);
        var minT = Double.MAX_VALUE;
        var maxT = -Double.MAX_VALUE;
        for (var texel = 0; texel < 16; texel++) {
            var t = 0.0;
            for (var c = 0; c < channels; c++) {
                t += (palette[texel * channels + c] - mean[c]) * axis[c];
            }
            minT = Math.min(minT, t);
            maxT = Math.max(maxT, t);
        }
        for (var c = 0; c < channels; c++) {
            low[c] = clamp(mean[c] + minT * axis[c]);
            high[c] = clamp(mean[c] + maxT * axis[c]);
        }
    }

    /**
     * Computes the principal axis of 16 points around {@link #mean} into {@link #axis} by power iteration; the axis is
     * zero when the points coincide.
     *
     * @param points the points, {@code channels} values each
     * @param channels the dimension, 3 or 4
     */
    private void principalAxis(int[] points, int channels) {
        var covariance = this.covariance;
        java.util.Arrays.fill(covariance, 0);
        var next = this.next;
        for (var texel = 0; texel < 16; texel++) {
            for (var i = 0; i < channels; i++) {
                var di = points[texel * channels + i] - mean[i];
                for (var j = 0; j < channels; j++) {
                    covariance[i * 4 + j] += di * (points[texel * channels + j] - mean[j]);
                }
            }
        }
        for (var c = 0; c < 4; c++) {
            axis[c] = c < channels ? 1 : 0;
        }
        for (var c = 0; c < channels; c++) {
            if (covariance[c * 4 + c] > covariance[0] && covariance[c * 4 + c] > 0) {
                for (var k = 0; k < 4; k++) {
                    axis[k] = k == c ? 1 : 0.25;
                }
            }
        }
        for (var iteration = 0; iteration < 16; iteration++) {
            var length = 0.0;
            for (var i = 0; i < channels; i++) {
                var v = 0.0;
                for (var j = 0; j < channels; j++) {
                    v += covariance[i * 4 + j] * axis[j];
                }
                next[i] = v;
                length += v * v;
            }
            if (length == 0) {
                for (var c = 0; c < 4; c++) {
                    axis[c] = 0;
                }
                return;
            }
            length = Math.sqrt(length);
            for (var i = 0; i < channels; i++) {
                axis[i] = next[i] / length;
            }
        }
    }

    private static double clamp(double value) {
        return Math.clamp(value, 0, 255);
    }

    /**
     * Appends a field to a 128-bit block from its least significant end.
     *
     * @param bits the block, low word first
     * @param at the index of the field's first bit
     * @param value the field
     * @param count the field width, from 1 to 32
     * @return the index after the field
     */
    private static int put(long[] bits, int at, int value, int count) {
        for (var i = 0; i < count; i++) {
            var bit = at + i;
            bits[bit >>> 6] |= (long) (value >>> i & 1) << (bit & 63);
        }
        return at + count;
    }

    private static void putInt(byte[] out, int offset, int value) {
        for (var i = 0; i < 4; i++) {
            out[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    private static void putLong(byte[] out, int offset, long value) {
        for (var i = 0; i < 8; i++) {
            out[offset + i] = (byte) (value >>> (8 * i));
        }
    }
}
