/* Ports libjxl 0.11.2 (lib/jxl/enc_adaptive_quantization.cc: AdaptiveQuantizationMap, ComputeMask, the gamma,
   blue and high-frequency modulations, MaskingSqrt and FuzzyErosion). Copyright the JPEG XL Project Authors; BSD
   3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.vardct;

/**
 * Computes the initial quantization field of a lossy frame from the visual masking of its XYB planes: busy areas
 * hide more quantization error than flat ones, dark and saturated blue areas less.
 *
 * @implNote This implementation evaluates the logarithms and powers of libjxl's fast approximations exactly, and
 * the whole frame at once rather than per tile.
 */
final class AdaptiveQuantization {
    private static final float SG_MUL = 226.77216153508914f;

    private static final float SG_MUL2 = 1.0f / 73.377132366608819f;

    private static final float LOG2 = 0.693147181f;

    private static final float SG_RET_MUL = SG_MUL2 * 18.6580932135f * LOG2;

    private static final float SG_V_OFFSET = 7.7825991679894591f;

    private AdaptiveQuantization() {
        throw new AssertionError();
    }

    /**
     * Computes the field.
     *
     * @param xyb the X, Y and B planes, whole 8x8 blocks
     * @param bw the width in blocks
     * @param bh the height in blocks
     * @param distance the distance target the field is computed for
     * @param scale the multiplier of the field, the AC quantization of the distance
     * @return the quantization field, one value per block
     */
    static float[] field(float[][] xyb, int bw, int bh, float distance, float scale) {
        var w = bw * 8;
        var h = bh * 8;
        var y = xyb[1];
        var pw = bw * 2;
        var ph = bh * 2;
        var pre = new float[pw * ph];
        var row = new float[w];
        for (var py = 0; py < h; py++) {
            var y1 = py > 0 ? py - 1 : py;
            var y2 = py + 1 < h ? py + 1 : py;
            for (var x = 0; x < w; x++) {
                var x1 = x > 0 ? x - 1 : x;
                var x2 = x + 1 < w ? x + 1 : x;
                var v = y[py * w + x];
                var base = 0.25f * (y[y2 * w + x] + y[y1 * w + x] + y[py * w + x1] + y[py * w + x2]);
                var gammac = ratio(v + 0.019f, false);
                var diff = gammac * (v - base);
                diff *= diff;
                if (diff >= 0.2f) {
                    diff = 0.2f;
                }
                diff = maskingSqrt(diff);
                row[x] = (py % 4) != 0 ? row[x] + diff : diff;
            }
            if (py % 4 == 3) {
                var out = (py / 4) * pw;
                for (var x = 0; x < pw; x++) {
                    pre[out + x] = (row[x * 4] + row[x * 4 + 1] + row[x * 4 + 2] + row[x * 4 + 3]) * 0.25f;
                }
            }
        }
        var aq = new float[bw * bh];
        erode(distance, pre, pw, ph, aq, bw);
        var baseLevel = 0.48f * scale;
        var dampen = 1.0f;
        if (distance >= 2.0f) {
            dampen = Math.max(0.0f, 1.0f - (distance - 2.0f) / (14.0f - 2.0f));
        }
        var mul = scale * dampen;
        var add = (1.0f - dampen) * baseLevel;
        for (var by = 0; by < bh; by++) {
            for (var bx = 0; bx < bw; bx++) {
                var v = computeMask(aq[by * bw + bx]);
                v = hfModulation(bx * 8, by * 8, y, w, v);
                v = gammaModulation(bx * 8, by * 8, xyb[0], y, w, v);
                v = blueModulation(bx * 8, by * 8, xyb[0], y, xyb[2], w, v);
                aq[by * bw + bx] = (float) StrictMath.exp(v) * mul + add;
            }
        }
        return aq;
    }

    static float ratio(float v, boolean invert) {
        var epsilon = 1e-2f;
        v = Math.max(v, 0.0f);
        var numMul = SG_RET_MUL * 3 * SG_MUL;
        var vOffset = SG_V_OFFSET * LOG2 + epsilon;
        var denMul = LOG2 * SG_MUL;
        var v2 = v * v;
        var num = numMul * v2 + epsilon;
        var den = denMul * v * v2 + vOffset;
        return invert ? num / den : den / num;
    }

    private static float maskingSqrt(float v) {
        var mul = 211.66567973503678f * 1e8f;
        return 0.25f * (float) Math.sqrt(v * (float) Math.sqrt(mul) + 27.505837037000106f);
    }

    private static float computeMask(float outVal) {
        var base = -0.7647f;
        var mul4 = 9.4708735624378946f;
        var mul2 = 17.35036561631863f;
        var offset2 = 302.59587815579727f;
        var mul3 = 6.7943250517376494f;
        var offset3 = 3.7179635626140772f;
        var offset4 = 0.25f * offset3;
        var mul0 = 0.80061762862741759f;
        var v1 = Math.max(outVal * mul0, 1e-3f);
        var v2 = 1.0f / (v1 + offset2);
        var v3 = 1.0f / (v1 * v1 + offset3);
        var v4 = 1.0f / (v1 * v1 + offset4);
        return base + mul4 * v4 + mul2 * v2 + mul3 * v3;
    }

    private static float hfModulation(int x, int y, float[] plane, int w, float outVal) {
        var sum = 0.0f;
        var valmin = 0.0206f;
        for (var dy = 0; dy < 8; dy++) {
            var r = (y + dy) * w + x;
            var next = dy == 7 ? r : r + w;
            for (var dx = 0; dx < 8; dx++) {
                var p = plane[r + dx];
                if (dx < 7) {
                    sum += Math.min(valmin, Math.abs(p - plane[r + dx + 1]));
                }
                sum += Math.min(valmin, Math.abs(p - plane[next + dx]));
            }
        }
        return outVal + sum * -0.38f + 0.42f;
    }

    private static float gammaModulation(int x, int y, float[] px, float[] py, int w, float outVal) {
        var overall = 0.0f;
        for (var dy = 0; dy < 8; dy++) {
            var r = (y + dy) * w + x;
            for (var dx = 0; dx < 8; dx++) {
                var iny = py[r + dx] + 0.16f;
                var inx = px[r + dx];
                overall += ratio(iny - inx, true);
                overall += ratio(iny + inx, true);
            }
        }
        overall *= 0.5f / 64;
        return outVal + 0.1005613337192697f * (float) (StrictMath.log(overall) / StrictMath.log(2));
    }

    private static float blueModulation(int x, int y, float[] px, float[] py, float[] pb, int w, float outVal) {
        var limit = 0.027121074570634722f;
        var offset = 0.084381641171960495f;
        var sum = 0.0f;
        for (var dy = 0; dy < 8; dy++) {
            var r = (y + dy) * w + x;
            for (var dx = 0; dx < 8; dx++) {
                var b = pb[r + dx];
                var effective = py[r + dx] + offset + Math.abs(px[r + dx]);
                if (b > effective) {
                    sum += Math.min(b - effective, limit);
                }
            }
        }
        if (sum >= 32 * limit) {
            sum = 64 * limit - sum;
        }
        var maxLimit = 15.398788439047934f;
        if (sum >= maxLimit * limit) {
            sum = maxLimit * limit;
        }
        return outVal + sum * 0.14207000358439159f;
    }

    private static void erode(float distance, float[] from, int fw, int fh, float[] to, int tw) {
        var mul = distance < 2.0f ? (2.0f - distance) * 0.5f : 0.0f;
        var k0 = 0.125f;
        var k1 = 0.10f + mul * -0.10f;
        var k2 = 0.09f + mul * -0.09f;
        var k3 = 0.06f + mul * -0.06f;
        var norm = 0.29959705784054957f / (k0 + k1 + k2 + k3);
        k0 *= norm;
        k1 *= norm;
        k2 *= norm;
        k3 *= norm;
        var m = new float[4];
        for (var fy = 0; fy < fh; fy++) {
            var ym1 = fy >= 1 ? fy - 1 : fy;
            var yp1 = fy + 1 < fh ? fy + 1 : fy;
            for (var fx = 0; fx < fw; fx++) {
                var xm1 = fx >= 1 ? fx - 1 : fx;
                var xp1 = fx + 1 < fw ? fx + 1 : fx;
                m[0] = from[fy * fw + fx];
                m[1] = from[fy * fw + xm1];
                m[2] = from[fy * fw + xp1];
                m[3] = from[ym1 * fw + xm1];
                java.util.Arrays.sort(m);
                storeMin4(from[ym1 * fw + fx], m);
                storeMin4(from[ym1 * fw + xp1], m);
                storeMin4(from[yp1 * fw + xm1], m);
                storeMin4(from[yp1 * fw + fx], m);
                storeMin4(from[yp1 * fw + xp1], m);
                var v = k0 * m[0] + k1 * m[1] + k2 * m[2] + k3 * m[3];
                var index = (fy / 2) * tw + fx / 2;
                if (fx % 2 == 0 && fy % 2 == 0) {
                    to[index] = v;
                } else {
                    to[index] += v;
                }
            }
        }
    }

    private static void storeMin4(float v, float[] m) {
        if (v < m[3]) {
            if (v < m[0]) {
                m[3] = m[2];
                m[2] = m[1];
                m[1] = m[0];
                m[0] = v;
            } else if (v < m[1]) {
                m[3] = m[2];
                m[2] = m[1];
                m[1] = v;
            } else if (v < m[2]) {
                m[3] = m[2];
                m[2] = v;
            } else {
                m[3] = v;
            }
        }
    }
}
