/* Ports libjxl 0.11.2 (lib/jxl/enc_xyb.cc: LinearRGBToXYB and the opsin absorbance parameters of
   lib/jxl/cms/opsin_params.h). Copyright the JPEG XL Project Authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.encode;

import com.github.auties00.smalt.image.jxl.internal.color.ColorMath;
import com.github.auties00.smalt.image.jxl.internal.header.ColorEncoding;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Converts samples to the XYB planes VarDCT codes.
 */
final class XybConverter {
    private static final float[] MATRIX = {
            0.30f, 1.0f - 0.078f - 0.30f, 0.078f,
            0.23f, 1.0f - 0.078f - 0.23f, 0.078f,
            0.24342268924547819f, 0.20476744424496821f, 1.0f - 0.24342268924547819f - 0.20476744424496821f};

    private static final float BIAS = 0.0037930732552754493f;

    private static final double[] SRGB_PRIMARIES = {0.639998686, 0.330010138, 0.300003784, 0.600003357, 0.150002046,
            0.059997204};

    private static final double[] D65 = {0.3127, 0.329};

    private XybConverter() {
        throw new AssertionError();
    }

    /**
     * Converts integer or floating-point color planes to XYB, padded to whole 8x8 blocks by edge replication.
     *
     * @param source the source, its color planes in the space its color encoding describes
     * @param intensityTarget the intensity target of the image header, in nits
     * @return the X, Y and B planes, {@code ceil(width / 8) * 8} samples wide
     */
    static float[][] convert(JxlImageEncoder.Source source, float intensityTarget) {
        var width = source.width();
        var height = source.height();
        var pw = (width + 7) / 8 * 8;
        var ph = (height + 7) / 8 * 8;
        var out = new float[3][pw * ph];
        var color = source.color();
        var gray = color.size() == 1;
        var enc = source.colorEncoding();
        var depth = source.depth();
        var floatSamples = depth.floatSample();
        var scale = floatSamples ? 1.0f : 1.0f / (float) ((1L << depth.bitsPerSample()) - 1);
        float[] toSrgb = null;
        var samePrimaries = gray || (enc.primaries() == ColorEncoding.PRIMARIES_SRGB
                && enc.whitePoint() == ColorEncoding.WHITE_D65);
        if (!samePrimaries) {
            var toXyz = ColorMath.rgbToXyz(enc.primariesXy(), enc.whiteXy());
            var adapt = ColorMath.adapt(enc.whiteXy(), D65);
            var fromXyz = ColorMath.invert(ColorMath.rgbToXyz(SRGB_PRIMARIES, D65));
            var m = ColorMath.multiply(fromXyz, ColorMath.multiply(adapt, toXyz));
            toSrgb = new float[9];
            for (var i = 0; i < 9; i++) {
                toSrgb[i] = (float) m[i];
            }
        }
        var linearScale = intensityTarget / 255.0f;
        var hlg = enc.transfer() == ColorEncoding.TF_HLG && !gray && (intensityTarget < 295 || intensityTarget > 305);
        var lum = ColorMath.rgbToXyz(enc.primariesXy(), enc.whiteXy());
        var lr = (float) lum[3];
        var lg = (float) lum[4];
        var lb = (float) lum[5];
        var hlgGamma = 1.2 * StrictMath.pow(1.111, StrictMath.log(intensityTarget / 1e3) / StrictMath.log(2));
        var cbrtBias = (float) StrictMath.cbrt(BIAS);
        var t = toSrgb;
        // About 16 sample operations per color sample: the transfer function and the cube roots.
        var bands = ParallelBands.bands(height, (long) width * height * 3 * 16);
        ParallelBands.run(bands, band -> {
            var y0 = (int) ((long) height * band / bands);
            var y1 = (int) ((long) height * (band + 1) / bands);
            for (var y = y0; y < y1; y++) {
                for (var x = 0; x < width; x++) {
                    var i = y * width + x;
                    float r;
                    float g;
                    float b;
                    if (gray) {
                        r = toLinear(sample(color.get(0)[i], floatSamples, scale), enc, intensityTarget);
                        g = r;
                        b = r;
                    } else {
                        r = toLinear(sample(color.get(0)[i], floatSamples, scale), enc, intensityTarget);
                        g = toLinear(sample(color.get(1)[i], floatSamples, scale), enc, intensityTarget);
                        b = toLinear(sample(color.get(2)[i], floatSamples, scale), enc, intensityTarget);
                        if (hlg) {
                            var ys = lr * r + lg * g + lb * b;
                            var mult = ys <= 0 ? 0.0f : (float) StrictMath.pow(ys, hlgGamma - 1);
                            r *= mult;
                            g *= mult;
                            b *= mult;
                        }
                        if (t != null) {
                            var r2 = t[0] * r + t[1] * g + t[2] * b;
                            var g2 = t[3] * r + t[4] * g + t[5] * b;
                            var b2 = t[6] * r + t[7] * g + t[8] * b;
                            r = r2;
                            g = g2;
                            b = b2;
                        }
                    }
                    r *= linearScale;
                    g *= linearScale;
                    b *= linearScale;
                    var l = (float) StrictMath.cbrt(MATRIX[0] * r + MATRIX[1] * g + MATRIX[2] * b + BIAS) - cbrtBias;
                    var m = (float) StrictMath.cbrt(MATRIX[3] * r + MATRIX[4] * g + MATRIX[5] * b + BIAS) - cbrtBias;
                    var s = (float) StrictMath.cbrt(MATRIX[6] * r + MATRIX[7] * g + MATRIX[8] * b + BIAS) - cbrtBias;
                    var o = y * pw + x;
                    out[0][o] = 0.5f * (l - m);
                    out[1][o] = 0.5f * (l + m);
                    out[2][o] = s;
                }
                for (var c = 0; c < 3; c++) {
                    var row = y * pw;
                    for (var x = width; x < pw; x++) {
                        out[c][row + x] = out[c][row + width - 1];
                    }
                }
            }
        });
        for (var c = 0; c < 3; c++) {
            for (var y = height; y < ph; y++) {
                System.arraycopy(out[c], (height - 1) * pw, out[c], y * pw, pw);
            }
        }
        return out;
    }

    private static float sample(int raw, boolean floatSamples, float scale) {
        return floatSamples ? Float.intBitsToFloat(raw) : raw * scale;
    }

    /**
     * Decodes a transfer function.
     *
     * @param v the encoded value, nominally from 0 to 1
     * @param enc the color encoding
     * @param intensityTarget the intensity target in nits, which PQ values are relative to
     * @return the linear value, 1 being the reference white
     */
    static float toLinear(float v, ColorEncoding enc, float intensityTarget) {
        var a = Math.abs(v);
        double e;
        switch (enc.transfer()) {
            case ColorEncoding.TF_SRGB -> e = a <= 0.04045 ? a / 12.92 : StrictMath.pow((a + 0.055) / 1.055, 2.4);
            case ColorEncoding.TF_BT709 -> e = a <= 0.081 ? a / 4.5 : StrictMath.pow((a + 0.099) / 1.099, 1 / 0.45);
            case ColorEncoding.TF_PQ -> {
                var p = StrictMath.pow(a, 4096.0 / (2523.0 * 128.0));
                var num = Math.max(p - 0.8359375, 0.0);
                var den = 18.8515625 - 18.6875 * p;
                e = StrictMath.pow(num / den, 16384.0 / 2610.0) * 10000.0 / intensityTarget;
            }
            case ColorEncoding.TF_DCI -> e = StrictMath.pow(a, 2.6);
            case ColorEncoding.TF_HLG -> e = a <= 0.5 ? a * a / 3.0
                    : (StrictMath.exp((a - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
            case ColorEncoding.TF_GAMMA -> e = StrictMath.pow(a, 1e7 / enc.gamma());
            default -> e = a;
        }
        return (float) Math.copySign(e, v);
    }
}
