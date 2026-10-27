package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.image.TransferCharacteristics;

/**
 * Transforms high-dynamic-range RGB (PQ or HLG) into a standard-dynamic-range profile.
 *
 * <p>Each pixel is decoded to display light in candelas per square meter: PQ through the
 * SMPTE ST 2084 EOTF, HLG through the inverse OETF and the BT.2100 OOTF of a 1000 candelas per
 * square meter display (system gamma 1.2, luminance taken from the source primaries). With
 * {@link ToneMapping#BT2390} the three components are then scaled by the ratio that the BT.2390
 * EETF applies to their maximum, compressing the content peak to the reference white; with
 * {@link ToneMapping#CLIP} they are left as they are. Dividing by the reference white and
 * clipping to {@code 0..1} gives linear light in the source primaries, which a linear-light
 * profile with those primaries converts to the target at 16 bits. The floating-point engine decodes
 * with the exact transfer functions and EETF, and hands the linear light to a floating-point
 * transform without quantizing or clipping it.
 *
 * <p>Instances are immutable and thread-safe.
 */
final class HdrEngine implements ColorEngine, FloatEngine {
    /**
     * The number of pixels converted per block.
     */
    private static final int BLOCK = 64;

    /**
     * Whether the source is HLG rather than PQ.
     */
    private final boolean hlg;

    /**
     * The decoded value of every 16-bit sample: candelas per square meter for PQ, normalized
     * scene light for HLG.
     */
    private final float[] decode;

    /**
     * The luminance weights of the source primaries.
     */
    private final float[] luminance;

    /**
     * The OOTF factor {@code 1000 * Ys^(gamma - 1)} at {@code Ys = (k / STEPS)^2}, for HLG.
     */
    private final float[] ootf;

    /**
     * The tone mapping ratio at a maximum component of {@code (k / STEPS)^2} times the source
     * peak, or {@code null} for no tone mapping.
     */
    private final float[] gain;

    /**
     * The source peak in candelas per square meter.
     */
    private final float peak;

    /**
     * The reciprocal of the reference white.
     */
    private final float inverseWhite;

    /**
     * The transform from linear light in the source primaries to the target, at 16 bits, or {@code null} for a
     * floating-point engine.
     */
    private final ColorEngine inner;

    /**
     * The floating-point transform from linear light in the source primaries to the target, or {@code null} for an
     * engine of 8 and 16-bit samples.
     */
    private final FloatEngine innerFloat;

    /**
     * The HLG system gamma of the reference display.
     */
    private final double gamma;

    /**
     * The reference white in candelas per square meter, which tone mapping compresses the source peak to.
     */
    private final double white;

    /**
     * Whether tone mapping compresses luminance.
     */
    private final boolean toneMapped;

    /**
     * The number of target channels.
     */
    private final int outputs;

    /**
     * Builds the engine.
     *
     * @param source the high-dynamic-range source
     * @param inner the 16-bit transform from linear light in the source primaries to the target
     * @param outputs the number of target channels
     * @param options the transform options
     */
    HdrEngine(ColorProfile.HdrSpec source, ColorEngine inner, int outputs, ColorTransformOptions options) {
        this(source, inner, null, outputs, options);
    }

    /**
     * Builds the floating-point engine.
     *
     * <p>Signals are clamped to {@code 0..1} and decoded exactly; the linear light the inner transform receives is
     * neither quantized nor clipped.
     *
     * @param source the high-dynamic-range source
     * @param inner the floating-point transform from linear light in the source primaries to the target
     * @param outputs the number of target channels
     * @param options the transform options
     */
    HdrEngine(ColorProfile.HdrSpec source, FloatEngine inner, int outputs, ColorTransformOptions options) {
        this(source, null, inner, outputs, options);
    }

    /**
     * Builds the engine of one depth.
     *
     * @param source the high-dynamic-range source
     * @param inner the 16-bit inner transform, or {@code null}
     * @param innerFloat the floating-point inner transform, or {@code null}
     * @param outputs the number of target channels
     * @param options the transform options
     */
    private HdrEngine(ColorProfile.HdrSpec source, ColorEngine inner, FloatEngine innerFloat, int outputs,
                      ColorTransformOptions options) {
        this.hlg = source.transfer() == TransferCharacteristics.HLG;
        this.decode = hlg ? Hdr.Tables.hlgDecode() : Hdr.Tables.pqDecode();
        var matrix = Hdr.rgbToXyz(source);
        this.luminance = new float[]{(float) matrix[3], (float) matrix[4], (float) matrix[5]};
        this.ootf = hlg ? Hdr.Tables.hlgOotf() : null;
        this.peak = (float) source.peak();
        var white = options.referenceWhite();
        if (options.toneMapping() == ToneMapping.BT2390 && source.peak() > white) {
            gain = new float[Hdr.STEPS + 2];
            for (var k = 0; k <= Hdr.STEPS; k++) {
                var nits = StrictMath.pow((double) k / Hdr.STEPS, 2) * source.peak();
                gain[k] = nits > 0 ? (float) (Hdr.eetf(nits, source.peak(), white) / nits) : 1f;
            }
            gain[Hdr.STEPS + 1] = gain[Hdr.STEPS];
        } else {
            gain = null;
        }
        this.inverseWhite = (float) (1.0 / white);
        this.inner = inner;
        this.innerFloat = innerFloat;
        this.outputs = outputs;
        this.gamma = Hdr.hlgGamma(Hdr.HLG_PEAK);
        this.white = white;
        this.toneMapped = gain != null;
    }

    /**
     * Converts decoded pixels to 16-bit linear light in the source primaries.
     *
     * @param r the red decoded value
     * @param g the green decoded value
     * @param b the blue decoded value
     * @param out the destination
     * @param index the index of the pixel in the destination
     */
    private void linearize(float r, float g, float b, short[] out, int index) {
        if (hlg) {
            var ys = luminance[0] * r + luminance[1] * g + luminance[2] * b;
            var factor = Hdr.lookupSqrt(ootf, ys);
            r *= factor;
            g *= factor;
            b *= factor;
        }
        var scale = inverseWhite;
        if (gain != null) {
            var max = Math.max(r, Math.max(g, b));
            scale *= Hdr.lookupGain(gain, max / peak);
        }
        out[index] = quantize(r * scale);
        out[index + 1] = quantize(g * scale);
        out[index + 2] = quantize(b * scale);
    }

    /**
     * Quantizes linear light to a 16-bit sample.
     *
     * @param value the linear value
     * @return the sample, {@code 0..65535} in a {@code short}
     */
    private static short quantize(float value) {
        return (short) Math.round(Math.min(Math.max(value, 0f), 1f) * 65535f);
    }

    @Override
    public void apply(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int count) {
        var linear = new short[BLOCK * 3];
        var result = new short[BLOCK * outputs];
        var table = decode;
        for (var start = 0; start < count; start += BLOCK) {
            var n = Math.min(BLOCK, count - start);
            for (var i = 0; i < n; i++) {
                var s = srcOffset + (start + i) * srcStride;
                linearize(table[(src[s] & 0xFF) * 257], table[(src[s + 1] & 0xFF) * 257], table[(src[s + 2] & 0xFF) * 257],
                        linear, i * 3);
            }
            inner.apply(linear, 0, 3, result, 0, outputs, n);
            for (var i = 0; i < n; i++) {
                var d = dstOffset + (start + i) * dstStride;
                for (var c = 0; c < outputs; c++) {
                    dst[d + c] = (byte) ColorMath.wordToByte(result[i * outputs + c] & 0xFFFF);
                }
            }
        }
    }

    @Override
    public void apply(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride, int count) {
        var linear = new short[BLOCK * 3];
        var table = decode;
        for (var start = 0; start < count; start += BLOCK) {
            var n = Math.min(BLOCK, count - start);
            for (var i = 0; i < n; i++) {
                var s = srcOffset + (start + i) * srcStride;
                linearize(table[src[s] & 0xFFFF], table[src[s + 1] & 0xFFFF], table[src[s + 2] & 0xFFFF], linear, i * 3);
            }
            inner.apply(linear, 0, 3, dst, dstOffset + start * dstStride, dstStride, n);
        }
    }

    @Override
    public void apply(float[] src, int srcOffset, int srcStride, float[] dst, int dstOffset, int dstStride, int count) {
        var linear = new float[BLOCK * 3];
        for (var start = 0; start < count; start += BLOCK) {
            var n = Math.min(BLOCK, count - start);
            for (var i = 0; i < n; i++) {
                var s = srcOffset + (start + i) * srcStride;
                linearize(decode(src[s]), decode(src[s + 1]), decode(src[s + 2]), linear, i * 3);
            }
            innerFloat.apply(linear, 0, 3, dst, dstOffset + start * dstStride, dstStride, n);
        }
    }

    /**
     * Decodes a signal exactly.
     *
     * @param signal the signal, clamped to {@code 0..1}
     * @return the luminance in candelas per square meter for PQ, the normalized scene light for HLG
     */
    private double decode(float signal) {
        return hlg ? Hdr.hlgToScene(signal) : Hdr.pqToNits(signal);
    }

    /**
     * Converts decoded pixels to linear light in the source primaries without quantizing or clipping it.
     *
     * @param r the red decoded value
     * @param g the green decoded value
     * @param b the blue decoded value
     * @param out the destination
     * @param index the index of the pixel in the destination
     */
    private void linearize(double r, double g, double b, float[] out, int index) {
        if (hlg) {
            var ys = luminance[0] * r + luminance[1] * g + luminance[2] * b;
            var factor = ys > 0 ? Hdr.HLG_PEAK * StrictMath.pow(ys, gamma - 1) : 0;
            r *= factor;
            g *= factor;
            b *= factor;
        }
        var scale = 1.0 / white;
        if (toneMapped) {
            var max = Math.max(r, Math.max(g, b));
            if (max > 0) {
                scale *= Hdr.eetf(max, peak, white) / max;
            }
        }
        out[index] = (float) (r * scale);
        out[index + 1] = (float) (g * scale);
        out[index + 2] = (float) (b * scale);
    }
}
