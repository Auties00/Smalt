/* Ports libjxl 0.11.2 (tools/ssimulacra2.cc: the SSIMULACRA 2 metric). Copyright the JPEG XL Project Authors;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.testkit.image;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;

/**
 * Scores the visual quality of a distorted sRGB image against its original with SSIMULACRA 2: 100 for identical
 * images, about 90 for visually lossless coding, 70 for high quality and lower for worse.
 *
 * <p>Alpha is blended over a mid-gray background first, as libjxl's {@code ssimulacra2} tool does. The blur is a
 * truncated separable Gaussian of sigma 1.5 instead of the tool's recursive Gaussian, so scores differ from the
 * tool's by a fraction of a point; comparisons between encoders scored here are unaffected.
 */
public final class Ssimulacra2 {
    private static final float C2 = 0.0009f;

    private static final int SCALES = 6;

    private static final float[] KERNEL = kernel();

    private static final double[] WEIGHT = {0.0, 0.0007376606707406586, 0.0, 0.0, 0.0007793481682867309, 0.0, 0.0,
            0.0004371155730107379, 0.0, 1.1041726426657346, 0.00066284834129271, 0.00015231632783718752, 0.0,
            0.0016406437456599754, 0.0, 1.8422455520539298, 11.441172603757666, 0.0, 0.0007989109436015163,
            0.000176816438078653, 0.0, 1.8787594979546387, 10.94906990605142, 0.0, 0.0007289346991508072,
            0.9677937080626833, 0.0, 0.00014003424285435884, 0.9981766977854967, 0.00031949755934435053,
            0.0004550992113792063, 0.0, 0.0, 0.0013648766163243398, 0.0, 0.0, 0.0, 0.0, 0.0, 7.466890328078848, 0.0,
            17.445833984131262, 0.0006235601634041466, 0.0, 0.0, 6.683678146179332, 0.00037724407979611296,
            1.027889937768264, 225.20515300849274, 0.0, 0.0, 19.213238186143016, 0.0011401524586618361,
            0.001237755635509985, 176.39317598450694, 0.0, 0.0, 24.43300999870476, 0.28520802612117757,
            0.0004485436923833408, 0.0, 0.0, 0.0, 34.77906344483772, 44.835625328877896, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0008680556573291698, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0005313191874358747, 0.0,
            0.00016533814161379112, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0004179171803251336, 0.0017290828234722833, 0.0,
            0.0020827005846636437, 0.0, 0.0, 8.826982764996862, 23.19243343998926, 0.0, 95.1080498811086,
            0.9863978034400682, 0.9834382792465353, 0.0012286405048278493, 171.2667255897307, 0.9807858872435379,
            0.0, 0.0, 0.0, 0.0005130064588990679, 0.0, 0.00010854057858411537};

    private Ssimulacra2() {
        throw new AssertionError();
    }

    /**
     * Scores a distorted picture against the original.
     *
     * @param original the original, an 8-bit or 16-bit RGBA picture
     * @param distorted the distorted picture of the same size
     * @return the score, at most 100
     */
    public static double score(Picture original, Picture distorted) {
        if (original.width() != distorted.width() || original.height() != distorted.height()) {
            throw new IllegalArgumentException("pictures of different sizes");
        }
        var a = linear(original);
        var b = linear(distorted);
        var w = original.width();
        var h = original.height();
        var averages = new double[SCALES][];
        var edges = new double[SCALES][];
        var scales = 0;
        for (var scale = 0; scale < SCALES; scale++) {
            if (w < 8 || h < 8) {
                break;
            }
            if (scale > 0) {
                var nw = (w + 1) / 2;
                var nh = (h + 1) / 2;
                a = downsample(a, w, h, nw, nh);
                b = downsample(b, w, h, nw, nh);
                w = nw;
                h = nh;
            }
            var x1 = xyb(a, w * h);
            var x2 = xyb(b, w * h);
            var mu1 = new float[3][];
            var mu2 = new float[3][];
            var s11 = new float[3][];
            var s22 = new float[3][];
            var s12 = new float[3][];
            for (var c = 0; c < 3; c++) {
                mu1[c] = blur(x1[c], w, h);
                mu2[c] = blur(x2[c], w, h);
                s11[c] = blur(multiply(x1[c], x1[c]), w, h);
                s22[c] = blur(multiply(x2[c], x2[c]), w, h);
                s12[c] = blur(multiply(x1[c], x2[c]), w, h);
            }
            averages[scale] = ssimMap(mu1, mu2, s11, s22, s12, w * h);
            edges[scale] = edgeDiff(x1, mu1, x2, mu2, w * h);
            scales++;
        }
        var ssim = 0.0;
        var i = 0;
        for (var c = 0; c < 3; c++) {
            for (var scale = 0; scale < SCALES; scale++) {
                for (var n = 0; n < 2; n++) {
                    var s = scale < scales ? averages[scale][c * 2 + n] : 0;
                    var ring = scale < scales ? edges[scale][c * 4 + n] : 0;
                    var blur = scale < scales ? edges[scale][c * 4 + n + 2] : 0;
                    ssim += WEIGHT[i++] * Math.abs(s);
                    ssim += WEIGHT[i++] * Math.abs(ring);
                    ssim += WEIGHT[i++] * Math.abs(blur);
                }
            }
        }
        ssim *= 0.9562382616834844;
        ssim = 2.326765642916932 * ssim - 0.020884521182843837 * ssim * ssim
                + 6.248496625763138e-05 * ssim * ssim * ssim;
        return ssim > 0 ? 100.0 - 10.0 * StrictMath.pow(ssim, 0.6276336467831387) : 100.0;
    }

    private static float[][] linear(Picture picture) {
        var w = picture.width();
        var h = picture.height();
        var out = new float[3][w * h];
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                float r;
                float g;
                float b;
                float alpha;
                switch (picture) {
                    case RgbaPicture p -> {
                        var o = y * p.stride() + x * 4;
                        var px = p.pixels();
                        r = (px[o] & 0xFF) / 255.0f;
                        g = (px[o + 1] & 0xFF) / 255.0f;
                        b = (px[o + 2] & 0xFF) / 255.0f;
                        alpha = p.hasAlpha() ? (px[o + 3] & 0xFF) / 255.0f : 1.0f;
                    }
                    case Rgba16Picture p -> {
                        var o = y * p.stride() + x * 4;
                        var px = p.pixels();
                        r = (px[o] & 0xFFFF) / 65535.0f;
                        g = (px[o + 1] & 0xFFFF) / 65535.0f;
                        b = (px[o + 2] & 0xFFFF) / 65535.0f;
                        alpha = p.hasAlpha() ? (px[o + 3] & 0xFFFF) / 65535.0f : 1.0f;
                    }
                    default -> throw new IllegalArgumentException("an RGBA picture is required");
                }
                var i = y * w + x;
                out[0][i] = alpha * srgbToLinear(r) + (1 - alpha) * 0.5f;
                out[1][i] = alpha * srgbToLinear(g) + (1 - alpha) * 0.5f;
                out[2][i] = alpha * srgbToLinear(b) + (1 - alpha) * 0.5f;
            }
        }
        return out;
    }

    private static float srgbToLinear(float v) {
        return v <= 0.04045f ? v / 12.92f : (float) StrictMath.pow((v + 0.055) / 1.055, 2.4);
    }

    private static float[][] downsample(float[][] in, int w, int h, int nw, int nh) {
        var out = new float[3][nw * nh];
        for (var c = 0; c < 3; c++) {
            for (var oy = 0; oy < nh; oy++) {
                for (var ox = 0; ox < nw; ox++) {
                    var sum = 0.0f;
                    for (var iy = 0; iy < 2; iy++) {
                        for (var ix = 0; ix < 2; ix++) {
                            var x = Math.min(ox * 2 + ix, w - 1);
                            var y = Math.min(oy * 2 + iy, h - 1);
                            sum += in[c][y * w + x];
                        }
                    }
                    out[c][oy * nw + ox] = sum * 0.25f;
                }
            }
        }
        return out;
    }

    private static float[][] xyb(float[][] rgb, int n) {
        var bias = 0.0037930732552754493f;
        var cbrtBias = (float) StrictMath.cbrt(bias);
        var out = new float[3][n];
        for (var i = 0; i < n; i++) {
            var r = rgb[0][i];
            var g = rgb[1][i];
            var b = rgb[2][i];
            var l = (float) StrictMath.cbrt(Math.max(0, 0.30f * r + 0.622f * g + 0.078f * b + bias)) - cbrtBias;
            var m = (float) StrictMath.cbrt(Math.max(0, 0.23f * r + 0.692f * g + 0.078f * b + bias)) - cbrtBias;
            var s = (float) StrictMath.cbrt(Math.max(0, 0.24342268924547819f * r + 0.20476744424496821f * g
                    + (1 - 0.24342268924547819f - 0.20476744424496821f) * b + bias)) - cbrtBias;
            var x = 0.5f * (l - m);
            var y = 0.5f * (l + m);
            out[0][i] = x * 14.0f + 0.42f;
            out[1][i] = y + 0.01f;
            out[2][i] = (s - y) + 0.55f;
        }
        return out;
    }

    private static float[] kernel() {
        var radius = 5;
        var k = new float[2 * radius + 1];
        var sum = 0.0;
        for (var i = -radius; i <= radius; i++) {
            k[i + radius] = (float) StrictMath.exp(-(i * i) / (2 * 1.5 * 1.5));
            sum += k[i + radius];
        }
        for (var i = 0; i < k.length; i++) {
            k[i] /= (float) sum;
        }
        return k;
    }

    private static float[] blur(float[] in, int w, int h) {
        var radius = KERNEL.length / 2;
        var tmp = new float[w * h];
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var sum = 0.0f;
                var weight = 0.0f;
                for (var k = -radius; k <= radius; k++) {
                    var xx = x + k;
                    if (xx >= 0 && xx < w) {
                        sum += KERNEL[k + radius] * in[y * w + xx];
                        weight += KERNEL[k + radius];
                    }
                }
                tmp[y * w + x] = sum / weight;
            }
        }
        var out = new float[w * h];
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var sum = 0.0f;
                var weight = 0.0f;
                for (var k = -radius; k <= radius; k++) {
                    var yy = y + k;
                    if (yy >= 0 && yy < h) {
                        sum += KERNEL[k + radius] * tmp[yy * w + x];
                        weight += KERNEL[k + radius];
                    }
                }
                out[y * w + x] = sum / weight;
            }
        }
        return out;
    }

    private static float[] multiply(float[] a, float[] b) {
        var out = new float[a.length];
        for (var i = 0; i < a.length; i++) {
            out[i] = a[i] * b[i];
        }
        return out;
    }

    private static double quartic(double x) {
        x *= x;
        return x * x;
    }

    private static double[] ssimMap(float[][] m1, float[][] m2, float[][] s11, float[][] s22, float[][] s12, int n) {
        var out = new double[6];
        for (var c = 0; c < 3; c++) {
            var sum = 0.0;
            var sum4 = 0.0;
            for (var i = 0; i < n; i++) {
                var mu1 = m1[c][i];
                var mu2 = m2[c][i];
                var numM = 1.0f - (mu1 - mu2) * (mu1 - mu2);
                var numS = 2 * (s12[c][i] - mu1 * mu2) + C2;
                var denomS = (s11[c][i] - mu1 * mu1) + (s22[c][i] - mu2 * mu2) + C2;
                var d = Math.max(1.0 - (numM * numS / denomS), 0.0);
                sum += d;
                sum4 += quartic(d);
            }
            out[c * 2] = sum / n;
            out[c * 2 + 1] = Math.sqrt(Math.sqrt(sum4 / n));
        }
        return out;
    }

    private static double[] edgeDiff(float[][] img1, float[][] mu1, float[][] img2, float[][] mu2, int n) {
        var out = new double[12];
        for (var c = 0; c < 3; c++) {
            var sums = new double[4];
            for (var i = 0; i < n; i++) {
                var d1 = (1.0 + Math.abs(img2[c][i] - mu2[c][i])) / (1.0 + Math.abs(img1[c][i] - mu1[c][i])) - 1.0;
                var artifact = Math.max(d1, 0.0);
                var lost = Math.max(-d1, 0.0);
                sums[0] += artifact;
                sums[1] += quartic(artifact);
                sums[2] += lost;
                sums[3] += quartic(lost);
            }
            out[c * 4] = sums[0] / n;
            out[c * 4 + 1] = Math.sqrt(Math.sqrt(sums[1] / n));
            out[c * 4 + 2] = sums[2] / n;
            out[c * 4 + 3] = Math.sqrt(Math.sqrt(sums[3] / n));
        }
        return out;
    }
}
