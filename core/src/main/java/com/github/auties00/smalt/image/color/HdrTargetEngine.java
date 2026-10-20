package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.image.TransferCharacteristics;

import java.util.Arrays;

/**
 * Transforms RGB, gray or CMYK pixels into a high-dynamic-range profile (PQ or HLG).
 *
 * <p>Each pixel is first brought to display light in candelas per square meter, in the target
 * primaries:
 * <ul>
 *   <li>a standard-dynamic-range source is converted at 16 bits to a profile with the target
 *       primaries and a gamma of 2, then decoded and scaled so that its white reaches the
 *       reference white (ITU-R BT.2408 places it at 203 candelas per square meter);</li>
 *   <li>a high-dynamic-range source is decoded as {@link HdrEngine} decodes it (HLG through the
 *       OOTF of the 1000 candelas per square meter reference display) and converted to the
 *       target primaries with a 3x3 matrix, negative components clipped to zero.</li>
 * </ul>
 * When the source peak (the reference white for a standard-dynamic-range source) exceeds the
 * target limit, {@link ToneMapping#BT2390} scales the three components by the ratio the BT.2390
 * EETF applies to their maximum; every component is then clipped to the limit. The limit is 1000
 * candelas per square meter for HLG and, for PQ, the peak of a target that describes its mastering
 * display or content light level, else 10000. PQ is encoded with the SMPTE ST 2084 inverse EOTF;
 * HLG with the BT.2100 inverse OOTF of the reference display (luminance taken from the target
 * primaries) and the OETF.
 *
 * <p>Instances are immutable and thread-safe.
 */
final class HdrTargetEngine implements ColorEngine {
    /**
     * The number of pixels converted per block.
     */
    private static final int BLOCK = 64;

    /**
     * The transform from the standard-dynamic-range source to gamma-2 light in the target
     * primaries, at 16 bits, or {@code null} for a high-dynamic-range source.
     */
    private final ColorEngine inner;

    /**
     * The number of source channels.
     */
    private final int inputs;

    /**
     * The luminance of a standard-dynamic-range white, in candelas per square meter.
     */
    private final float white;

    /**
     * Whether the high-dynamic-range source is HLG rather than PQ.
     */
    private final boolean sourceHlg;

    /**
     * The decoded value of every 16-bit source sample, or {@code null} for a standard-dynamic-range
     * source.
     */
    private final float[] decode;

    /**
     * The luminance weights of the source primaries, for the HLG OOTF.
     */
    private final float[] sourceLuminance;

    /**
     * The 3x3 matrix from the source primaries to the target primaries in row-major order, or
     * {@code null} when they are the same.
     */
    private final float[] gamut;

    /**
     * The source peak in candelas per square meter.
     */
    private final float peak;

    /**
     * The tone mapping ratio at a maximum component of {@code (k / STEPS)^2} times the source
     * peak, or {@code null} for no tone mapping.
     */
    private final float[] gain;

    /**
     * The largest luminance a component keeps, in candelas per square meter.
     */
    private final float limit;

    /**
     * Whether the target is HLG rather than PQ.
     */
    private final boolean targetHlg;

    /**
     * The luminance weights of the target primaries, for the HLG inverse OOTF.
     */
    private final float[] targetLuminance;

    /**
     * The encoding table: {@link Hdr.Tables#pqEncode()} or {@link Hdr.Tables#hlgEncode()}.
     */
    private final float[] encode;

    /**
     * Constructs an engine.
     *
     * @param inner the 16-bit transform of a standard-dynamic-range source, or {@code null}
     * @param inputs the number of source channels
     * @param source the high-dynamic-range source, or {@code null}
     * @param target the high-dynamic-range target
     * @param options the transform options
     */
    private HdrTargetEngine(ColorEngine inner, int inputs, ColorProfile.HdrSpec source, ColorProfile.HdrSpec target,
                            ColorTransformOptions options) {
        this.inner = inner;
        this.inputs = inputs;
        this.white = (float) options.referenceWhite();
        if (source != null) {
            this.sourceHlg = source.transfer() == TransferCharacteristics.HLG;
            this.decode = sourceHlg ? Hdr.Tables.hlgDecode() : Hdr.Tables.pqDecode();
            this.sourceLuminance = luminance(source);
            var matrix = Hdr.gamutMatrix(source, target);
            this.gamut = ColorMath.isIdentity(matrix) ? null : toFloat(matrix);
        } else {
            this.sourceHlg = false;
            this.decode = null;
            this.sourceLuminance = null;
            this.gamut = null;
        }
        var sourcePeak = source != null ? source.peak() : options.referenceWhite();
        var targetLimit = limit(target);
        this.peak = (float) sourcePeak;
        this.limit = (float) targetLimit;
        if (options.toneMapping() == ToneMapping.BT2390 && sourcePeak > targetLimit) {
            gain = new float[Hdr.STEPS + 2];
            for (var k = 0; k <= Hdr.STEPS; k++) {
                var nits = Math.pow((double) k / Hdr.STEPS, 2) * sourcePeak;
                gain[k] = nits > 0 ? (float) (Hdr.eetf(nits, sourcePeak, targetLimit) / nits) : 1f;
            }
            gain[Hdr.STEPS + 1] = gain[Hdr.STEPS];
        } else {
            gain = null;
        }
        this.targetHlg = target.transfer() == TransferCharacteristics.HLG;
        this.targetLuminance = luminance(target);
        this.encode = targetHlg ? Hdr.Tables.hlgEncode() : Hdr.Tables.pqEncode();
    }

    /**
     * Returns the engine from a standard-dynamic-range source.
     *
     * @param inner the 16-bit transform from the source to a profile with the target primaries and a pure power
     * curve of exponent 2
     * @param inputs the number of source channels
     * @param target the high-dynamic-range target
     * @param options the transform options, whose reference white places the source white
     * @return the engine
     */
    static HdrTargetEngine fromStandard(ColorEngine inner, int inputs, ColorProfile.HdrSpec target,
                                        ColorTransformOptions options) {
        return new HdrTargetEngine(inner, inputs, null, target, options);
    }

    /**
     * Returns the engine from a high-dynamic-range source.
     *
     * @param source the high-dynamic-range source
     * @param target the high-dynamic-range target
     * @param options the transform options
     * @return the engine
     */
    static HdrTargetEngine fromHigh(ColorProfile.HdrSpec source, ColorProfile.HdrSpec target,
                                    ColorTransformOptions options) {
        return new HdrTargetEngine(null, 3, source, target, options);
    }

    /**
     * Returns whether a transform between two high-dynamic-range profiles leaves every value unchanged: the same
     * transfer characteristics and chromaticities, and a source peak within the target limit.
     *
     * @param source the high-dynamic-range source
     * @param target the high-dynamic-range target
     * @return {@code true} for an identity transform
     */
    static boolean isIdentity(ColorProfile.HdrSpec source, ColorProfile.HdrSpec target) {
        return source.transfer() == target.transfer() && Arrays.equals(source.red(), target.red())
                && Arrays.equals(source.green(), target.green())
                && Arrays.equals(source.blue(), target.blue())
                && Arrays.equals(source.white(), target.white())
                && source.peak() <= limit(target);
    }

    /**
     * Returns the largest luminance a high-dynamic-range target holds.
     *
     * @param target the high-dynamic-range target
     * @return the limit in candelas per square meter: 1000 for HLG; for PQ the peak when the target describes its
     * mastering display or content light level, else 10000
     */
    static double limit(ColorProfile.HdrSpec target) {
        if (target.transfer() == TransferCharacteristics.HLG) {
            return Hdr.HLG_PEAK;
        }
        var color = target.color();
        return color.masteringDisplay().isPresent() || color.contentLightLevel().isPresent() ? target.peak()
                : Hdr.PQ_PEAK;
    }

    /**
     * Returns the luminance weights of the primaries of a profile.
     *
     * @param spec the profile
     * @return the weights of red, green and blue
     */
    private static float[] luminance(ColorProfile.HdrSpec spec) {
        var matrix = Hdr.rgbToXyz(spec);
        return new float[]{(float) matrix[3], (float) matrix[4], (float) matrix[5]};
    }

    /**
     * Narrows a matrix to single precision.
     *
     * @param matrix the coefficients
     * @return the narrowed coefficients
     */
    private static float[] toFloat(double[] matrix) {
        var out = new float[matrix.length];
        for (var i = 0; i < matrix.length; i++) {
            out[i] = (float) matrix[i];
        }
        return out;
    }

    /**
     * Converts the decoded values of a high-dynamic-range source pixel to display light in the target primaries.
     *
     * @param r the red decoded value
     * @param g the green decoded value
     * @param b the blue decoded value
     * @param light receives the red, green and blue luminance
     */
    private void sourceLight(float r, float g, float b, float[] light) {
        if (sourceHlg) {
            var ys = sourceLuminance[0] * r + sourceLuminance[1] * g + sourceLuminance[2] * b;
            var factor = ys > 0f ? (float) (Hdr.HLG_PEAK * Math.pow(ys, Hdr.HLG_GAMMA - 1)) : 0f;
            r *= factor;
            g *= factor;
            b *= factor;
        }
        var m = gamut;
        if (m != null) {
            light[0] = m[0] * r + m[1] * g + m[2] * b;
            light[1] = m[3] * r + m[4] * g + m[5] * b;
            light[2] = m[6] * r + m[7] * g + m[8] * b;
        } else {
            light[0] = r;
            light[1] = g;
            light[2] = b;
        }
    }

    /**
     * Tone maps, clips and encodes the display light of a pixel.
     *
     * @param light the red, green and blue luminance; overwritten with the signals, {@code 0..1}
     */
    private void encode(float[] light) {
        var r = Math.max(light[0], 0f);
        var g = Math.max(light[1], 0f);
        var b = Math.max(light[2], 0f);
        if (gain != null) {
            var scale = Hdr.lookupSqrt(gain, Math.max(r, Math.max(g, b)) / peak);
            r *= scale;
            g *= scale;
            b *= scale;
        }
        r = Math.min(r, limit);
        g = Math.min(g, limit);
        b = Math.min(b, limit);
        if (targetHlg) {
            var inverse = (float) (1.0 / Hdr.HLG_PEAK);
            r *= inverse;
            g *= inverse;
            b *= inverse;
            var yd = targetLuminance[0] * r + targetLuminance[1] * g + targetLuminance[2] * b;
            // the inverse OOTF of a 1000-nit display: E = Yd^((1 - gamma) / gamma) * Fd, (1 - 1.2) / 1.2 = -1 / 6
            var factor = yd > 0f ? (float) (1.0 / Math.sqrt(Math.cbrt(yd))) : 0f;
            light[0] = Hdr.lookupSqrt(encode, r * factor);
            light[1] = Hdr.lookupSqrt(encode, g * factor);
            light[2] = Hdr.lookupSqrt(encode, b * factor);
        } else {
            var inverse = (float) (1.0 / Hdr.PQ_PEAK);
            light[0] = Hdr.lookupFourthRoot(encode, r * inverse);
            light[1] = Hdr.lookupFourthRoot(encode, g * inverse);
            light[2] = Hdr.lookupFourthRoot(encode, b * inverse);
        }
    }

    /**
     * Converts a block of 16-bit gamma-2 samples in the target primaries to display light.
     *
     * @param samples the samples, three per pixel
     * @param index the index of the pixel's first sample
     * @param light receives the red, green and blue luminance
     */
    private void standardLight(short[] samples, int index, float[] light) {
        var scale = white;
        for (var c = 0; c < 3; c++) {
            var x = (samples[index + c] & 0xFFFF) * (1f / 65535f);
            light[c] = x * x * scale;
        }
    }

    @Override
    public void apply(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int count) {
        var light = new float[3];
        if (inner == null) {
            var table = decode;
            for (var i = 0; i < count; i++) {
                var s = srcOffset + i * srcStride;
                sourceLight(table[(src[s] & 0xFF) * 257], table[(src[s + 1] & 0xFF) * 257],
                        table[(src[s + 2] & 0xFF) * 257], light);
                encode(light);
                var d = dstOffset + i * dstStride;
                dst[d] = (byte) (int) (light[0] * 255f + 0.5f);
                dst[d + 1] = (byte) (int) (light[1] * 255f + 0.5f);
                dst[d + 2] = (byte) (int) (light[2] * 255f + 0.5f);
            }
            return;
        }
        var wide = new short[BLOCK * inputs];
        var samples = new short[BLOCK * 3];
        for (var start = 0; start < count; start += BLOCK) {
            var n = Math.min(BLOCK, count - start);
            for (var i = 0; i < n; i++) {
                var s = srcOffset + (start + i) * srcStride;
                for (var c = 0; c < inputs; c++) {
                    wide[i * inputs + c] = (short) ((src[s + c] & 0xFF) * 257);
                }
            }
            inner.apply(wide, 0, inputs, samples, 0, 3, n);
            for (var i = 0; i < n; i++) {
                standardLight(samples, i * 3, light);
                encode(light);
                var d = dstOffset + (start + i) * dstStride;
                dst[d] = (byte) (int) (light[0] * 255f + 0.5f);
                dst[d + 1] = (byte) (int) (light[1] * 255f + 0.5f);
                dst[d + 2] = (byte) (int) (light[2] * 255f + 0.5f);
            }
        }
    }

    @Override
    public void apply(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride, int count) {
        var light = new float[3];
        if (inner == null) {
            var table = decode;
            for (var i = 0; i < count; i++) {
                var s = srcOffset + i * srcStride;
                sourceLight(table[src[s] & 0xFFFF], table[src[s + 1] & 0xFFFF], table[src[s + 2] & 0xFFFF], light);
                encode(light);
                var d = dstOffset + i * dstStride;
                dst[d] = (short) (int) (light[0] * 65535f + 0.5f);
                dst[d + 1] = (short) (int) (light[1] * 65535f + 0.5f);
                dst[d + 2] = (short) (int) (light[2] * 65535f + 0.5f);
            }
            return;
        }
        var samples = new short[BLOCK * 3];
        for (var start = 0; start < count; start += BLOCK) {
            var n = Math.min(BLOCK, count - start);
            inner.apply(src, srcOffset + start * srcStride, srcStride, samples, 0, 3, n);
            for (var i = 0; i < n; i++) {
                standardLight(samples, i * 3, light);
                encode(light);
                var d = dstOffset + (start + i) * dstStride;
                dst[d] = (short) (int) (light[0] * 65535f + 0.5f);
                dst[d + 1] = (short) (int) (light[1] * 65535f + 0.5f);
                dst[d + 2] = (short) (int) (light[2] * 65535f + 0.5f);
            }
        }
    }
}
