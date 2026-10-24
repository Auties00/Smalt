/* Ports jxl-oxide 0.12.6 (crates/jxl-color) and libjxl (lib/jxl/cms, lib/jxl/base/fast_math-inl.h). Copyright
   Wonwoo Choi and the jxl-oxide contributors, and the JPEG XL Project Authors; MIT License and BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.color;

import com.github.auties00.smalt.image.jxl.internal.header.ColorEncoding;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Converts decoded color planes to their output color space: XYB to RGB or gray in the image's
 * signaled encoding (ISO/IEC 18181-1 section L.2), YCbCr to RGB (section L.4), and the transfer
 * functions between linear and encoded values.
 *
 * <p>Samples are nominal floats, 1.0 meaning the image's intensity target for linear light.
 * Conversions run in place over the rows of the planes on the shared helper threads.
 *
 * @implNote This implementation uses exact transfer functions where jxl-oxide uses rational
 * approximations, maps out-of-gamut colors with a plain matrix, and extends the transfer
 * functions to negative values as libjxl does: sRGB, PQ and HLG are mirrored, BT.709 keeps its
 * linear segment, and pure power curves flush values up to {@code 1e-5} to zero.
 */
public final class ColorConverter {
    /**
     * The sRGB primaries.
     */
    private static final double[] SRGB_PRIMARIES = {0.639998686, 0.330010138, 0.300003784, 0.600003357, 0.150002046,
            0.059997204};

    /**
     * The D65 white point.
     */
    private static final double[] D65 = {0.3127, 0.329};

    /**
     * Prevents instantiation.
     */
    private ColorConverter() {
        throw new AssertionError();
    }

    /**
     * Returns the encoding XYB images convert to: the signaled enumerated RGB or gray encoding,
     * or linear sRGB (linear gray for grayscale images) when the image carries an ICC profile.
     *
     * @param image the image header
     * @return the target encoding
     */
    public static ColorEncoding xybTarget(ImageHeader image) {
        var enc = image.colorEncoding();
        if (!enc.wantIcc() && (enc.colorSpace() == ColorEncoding.RGB || enc.colorSpace() == ColorEncoding.GRAY)) {
            return enc;
        }
        var space = enc.colorSpace() == ColorEncoding.GRAY ? ColorEncoding.GRAY : ColorEncoding.RGB;
        return new ColorEncoding(false, space, ColorEncoding.WHITE_D65, null, ColorEncoding.PRIMARIES_SRGB, null,
                ColorEncoding.TF_LINEAR, 0, 1);
    }

    /**
     * Converts XYB planes to an RGB or gray encoding.
     *
     * @param planes the X, Y and B planes, converted in place
     * @param length the number of samples per plane
     * @param image the image header
     * @param target the target encoding, RGB or gray
     * @return the number of output channels: 3, or 1 for gray (left in plane 0)
     */
    public static int xybToTarget(float[][] planes, int length, ImageHeader image, ColorEncoding target) {
        return xybToTarget(planes, length, image, target, null);
    }

    /**
     * Converts XYB planes to an RGB or gray encoding, running a step on the linear planes before the transfer
     * function: on the three RGB planes for an RGB target, and on plane 0, holding the luminance, for a gray one.
     *
     * @param planes the X, Y and B planes, converted in place
     * @param length the number of samples per plane
     * @param image the image header
     * @param target the target encoding, RGB or gray
     * @param linear the step to run on the linear planes, or {@code null} for none
     * @return the number of output channels: 3, or 1 for gray (left in plane 0)
     */
    public static int xybToTarget(float[][] planes, int length, ImageHeader image, ColorEncoding target,
                                  Runnable linear) {
        var oim = image.opsinInverse();
        var itScale = 255.0f / image.toneMapping().intensityTarget();
        var bias = oim.opsinBias();
        var cbrt = new float[]{(float) StrictMath.cbrt(bias[0]), (float) StrictMath.cbrt(bias[1]), (float) StrictMath.cbrt(bias[2])};
        var m = oim.matrix();
        var toTarget = (double[]) null;
        var gray = target.colorSpace() == ColorEncoding.GRAY;
        var primaries = gray ? SRGB_PRIMARIES : target.primariesXy();
        var white = target.whiteXy();
        var sameSpace = !gray && target.primaries() == ColorEncoding.PRIMARIES_SRGB
                && target.whitePoint() == ColorEncoding.WHITE_D65;
        if (!sameSpace) {
            var toXyz = ColorMath.rgbToXyz(SRGB_PRIMARIES, D65);
            var adapt = ColorMath.adapt(D65, white);
            var fromXyz = ColorMath.invert(ColorMath.rgbToXyz(primaries, white));
            toTarget = ColorMath.multiply(fromXyz, ColorMath.multiply(adapt, toXyz));
        }
        var px = planes[0];
        var py = planes[1];
        var pb = planes[2];
        var t = toTarget == null ? null : toFloats(toTarget);
        var bands = ParallelBands.bands(Math.max(1, length >> 12), length);
        ParallelBands.run(bands, band -> {
            var from = (int) ((long) length * band / bands);
            var to = (int) ((long) length * (band + 1) / bands);
            for (var i = from; i < to; i++) {
                var gl = py[i] + px[i] - cbrt[0];
                var gm = py[i] - px[i] - cbrt[1];
                var gs = pb[i] - cbrt[2];
                var l = (gl * gl * gl + bias[0]) * itScale;
                var mm = (gm * gm * gm + bias[1]) * itScale;
                var s = (gs * gs * gs + bias[2]) * itScale;
                var r = m[0] * l + m[1] * mm + m[2] * s;
                var g = m[3] * l + m[4] * mm + m[5] * s;
                var bl = m[6] * l + m[7] * mm + m[8] * s;
                if (t != null) {
                    var r2 = t[0] * r + t[1] * g + t[2] * bl;
                    var g2 = t[3] * r + t[4] * g + t[5] * bl;
                    var b2 = t[6] * r + t[7] * g + t[8] * bl;
                    r = r2;
                    g = g2;
                    bl = b2;
                }
                px[i] = r;
                py[i] = g;
                pb[i] = bl;
            }
        });
        if (gray) {
            var lum = ColorMath.rgbToXyz(SRGB_PRIMARIES, D65);
            var adapt = ColorMath.multiply(ColorMath.adapt(D65, white), lum);
            var kr = (float) adapt[3];
            var kg = (float) adapt[4];
            var kb = (float) adapt[5];
            for (var i = 0; i < length; i++) {
                px[i] = kr * px[i] + kg * py[i] + kb * pb[i];
            }
            if (linear != null) {
                linear.run();
            }
            encode(new float[][]{px}, 1, length, target, image.toneMapping().intensityTarget());
            return 1;
        }
        if (linear != null) {
            linear.run();
        }
        encode(planes, 3, length, target, image.toneMapping().intensityTarget());
        return 3;
    }

    /**
     * Converts a double matrix to floats.
     *
     * @param m the matrix
     * @return the float matrix
     */
    private static float[] toFloats(double[] m) {
        var out = new float[m.length];
        for (var i = 0; i < m.length; i++) {
            out[i] = (float) m[i];
        }
        return out;
    }

    /**
     * Converts YCbCr planes to RGB in place (ISO/IEC 18181-1 section L.4).
     *
     * @param cb the Cb plane, replaced by red
     * @param y the Y plane, replaced by green
     * @param cr the Cr plane, replaced by blue
     * @param length the number of samples
     */
    public static void ycbcrToRgb(float[] cb, float[] y, float[] cr, int length) {
        for (var i = 0; i < length; i++) {
            var vcb = cb[i];
            var vy = y[i] + 128.0f / 255.0f;
            var vcr = cr[i];
            cb[i] = Math.fma(vcr, 1.402f, vy);
            y[i] = Math.fma(vcb, -0.114f * 1.772f / 0.587f, Math.fma(vcr, -0.299f * 1.402f / 0.587f, vy));
            cr[i] = Math.fma(vcb, 1.772f, vy);
        }
    }

    /**
     * Applies the transfer function of an encoding to linear planes.
     *
     * @param planes the linear planes, encoded in place
     * @param channels the number of color planes, 1 or 3
     * @param length the number of samples per plane
     * @param target the encoding
     * @param intensityTarget the luminance of linear 1.0, in candelas per square meter
     */
    public static void encode(float[][] planes, int channels, int length, ColorEncoding target, float intensityTarget) {
        var tf = target.transfer();
        if (tf == ColorEncoding.TF_LINEAR || tf == ColorEncoding.TF_UNKNOWN) {
            return;
        }
        if (tf == ColorEncoding.TF_HLG && channels == 3) {
            var lum = ColorMath.rgbToXyz(target.primariesXy(), target.whiteXy());
            hlgInverseOotf(planes, length, (float) lum[3], (float) lum[4], (float) lum[5], intensityTarget);
        }
        var chunks = Math.max(1, length >> 14);
        var bands = ParallelBands.bands(chunks * channels, (long) length * channels * 16);
        ParallelBands.run(bands, band -> {
            var from = ParallelBands.start(band, bands, chunks * channels);
            var to = ParallelBands.start(band + 1, bands, chunks * channels);
            for (var chunk = from; chunk < to; chunk++) {
                var plane = planes[chunk / chunks];
                var start = (int) ((long) (chunk % chunks) * length / chunks);
                var end = (int) ((long) (chunk % chunks + 1) * length / chunks);
                if (tf == ColorEncoding.TF_SRGB) {
                    SrgbKernel.INSTANCE.encode(plane, start, end);
                    continue;
                }
                for (var i = start; i < end; i++) {
                    plane[i] = fromLinear(plane[i], target, intensityTarget);
                }
            }
        });
    }

    /**
     * Applies the inverse HLG opto-optical transfer function, mapping display light back to scene
     * light.
     *
     * @param planes the RGB planes
     * @param length the number of samples
     * @param lr the red luminance coefficient
     * @param lg the green luminance coefficient
     * @param lb the blue luminance coefficient
     * @param intensityTarget the display peak
     */
    private static void hlgInverseOotf(float[][] planes, int length, float lr, float lg, float lb,
                                       float intensityTarget) {
        if (intensityTarget >= 295 && intensityTarget <= 305) {
            return;
        }
        var gamma = 1.2 * StrictMath.pow(1.111, StrictMath.log(intensityTarget / 1e3) / StrictMath.log(2));
        var exponent = (1 - gamma) / gamma;
        for (var i = 0; i < length; i++) {
            var mixed = planes[0][i] * lr + planes[1][i] * lg + planes[2][i] * lb;
            var mult = (float) StrictMath.pow(mixed, exponent);
            planes[0][i] *= mult;
            planes[1][i] *= mult;
            planes[2][i] *= mult;
        }
    }

    /**
     * Raises a positive value to a power, within about 1e-6 relative error.
     *
     * <p>The power is {@code 2^(e log2(b))}, with the base-2 logarithm a rational polynomial of the
     * mantissa reduced to [2/3, 4/3) plus the exponent, and the power of two a rational polynomial
     * of the fractional part scaled by the integer part.
     *
     * @implNote This implementation uses libjxl's {@code FastPowf}, {@code FastLog2f} and
     * {@code FastPow2f} (lib/jxl/base/fast_math-inl.h, BSD-3), evaluated with separate
     * multiplications and additions and an exact division.
     *
     * @param b the base, positive and finite
     * @param e the exponent
     * @return the power
     */
    static float fastPow(float b, float e) {
        var bits = Float.floatToRawIntBits(b);
        var exponent = (bits - 0x3F2AAAAB) >> 23;
        var m = Float.intBitsToFloat(bits - (exponent << 23)) - 1.0f;
        var lp = 7.4245873327820566E-01f;
        lp = lp * m + 1.4287160470083755E+00f;
        lp = lp * m - 1.8503833400518310E-06f;
        var lq = 1.7409343003366853E-01f;
        lq = lq * m + 1.0096718572241148E+00f;
        lq = lq * m + 9.9032814277590719E-01f;
        var x = (lp / lq + exponent) * e;
        var floor = (float) Math.floor(x);
        var scale = Float.intBitsToFloat(((int) floor + 127) << 23);
        var frac = x - floor;
        var num = frac + 1.01749063e+01f;
        num = num * frac + 4.88687798e+01f;
        num = num * frac + 9.85506591e+01f;
        num *= scale;
        var den = frac * 2.10242958e-01f - 2.22328856e-02f;
        den = den * frac - 1.94414990e+01f;
        den = den * frac + 9.85506633e+01f;
        return num / den;
    }

    /**
     * Encodes the magnitude of a linear value with the sRGB transfer function.
     *
     * <p>Above the linear segment the curve is a rational polynomial in the square root of the
     * value, within 5e-7 of {@code 1.055 v^(1/2.4) - 0.055}.
     *
     * @implNote This implementation uses libjxl's approximation ({@code TF_SRGB::EncodedFromDisplay}
     * in lib/jxl/cms/transfer_functions-inl.h, BSD-3), evaluated with separate multiplications and
     * additions and an exact division.
     *
     * @param a the linear magnitude, not negative
     * @return the encoded magnitude
     */
    static float srgbFromLinear(float a) {
        if (a <= 0.0031308f) {
            return 12.92f * a;
        }
        var x = (float) Math.sqrt(a);
        var yp = 7.352629620e-01f;
        yp = yp * x + 1.474205315e+00f;
        yp = yp * x + 3.903842876e-01f;
        yp = yp * x + 5.287254571e-03f;
        yp = yp * x - 5.135152395e-04f;
        var yq = 2.424867759e-02f;
        yq = yq * x + 9.258482155e-01f;
        yq = yq * x + 1.340816930e+00f;
        yq = yq * x + 3.036675394e-01f;
        yq = yq * x + 1.004519624e-02f;
        return yp / yq;
    }

    /**
     * Encodes a linear value with a transfer function.
     *
     * @param v the linear value
     * @param target the encoding
     * @param intensityTarget the luminance of linear 1.0
     * @return the encoded value
     */
    public static float fromLinear(float v, ColorEncoding target, float intensityTarget) {
        var a = Math.abs(v);
        double e;
        switch (target.transfer()) {
            case ColorEncoding.TF_SRGB -> {
                return ScalarSrgbKernel.encode(v);
            }
            case ColorEncoding.TF_BT709 -> {
                return v <= 0.018f ? 4.5f * v : 1.099f * fastPow(v, 0.45f) - 0.099f;
            }
            case ColorEncoding.TF_PQ -> {
                var y = StrictMath.pow(a * intensityTarget / 10000.0, 2610.0 / 16384.0);
                e = StrictMath.pow((0.8359375 + 18.8515625 * y) / (1 + 18.6875 * y), 2523.0 / 4096.0 * 128.0);
            }
            case ColorEncoding.TF_HLG -> e = a <= 1.0 / 12.0 ? Math.sqrt(3 * a)
                    : 0.17883277 * StrictMath.log(12 * a - 0.28466892) + 0.55991073;
            case ColorEncoding.TF_DCI -> {
                return v <= 1e-5f ? 0.0f : fastPow(v, 1 / 2.6f);
            }
            case ColorEncoding.TF_GAMMA -> {
                return v <= 1e-5f ? 0.0f : fastPow(v, (float) (target.gamma() / 1e7));
            }
            default -> e = a;
        }
        return (float) Math.copySign(e, v);
    }
}
