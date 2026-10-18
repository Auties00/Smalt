package com.github.auties00.smalt.image.color;

/**
 * Applies one lookup table per channel, for transforms made only of per-channel curves.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @implNote This implementation follows Little CMS 2.17's {@code OptimizeByJoiningCurves} ({@code cmsopt.c}): the
 * curves are composed by evaluating the pipeline at 4096 evenly spaced inputs, and the resulting 16-bit curve is
 * sampled at every 8-bit or 16-bit input.
 */
final class CurvesEngine implements ColorEngine {
    /**
     * The number of samples of the composed curves.
     */
    static final int POINTS = 4096;

    /**
     * The 8-bit outputs per channel and 8-bit input, or {@code null} for a 16-bit engine.
     */
    private final byte[][] tables8;

    /**
     * The 16-bit outputs per channel and 16-bit input, or {@code null} for an 8-bit engine.
     */
    private final char[][] tables16;

    /**
     * Constructs an engine.
     *
     * @param tables8 the 8-bit tables, or {@code null}
     * @param tables16 the 16-bit tables, or {@code null}
     */
    private CurvesEngine(byte[][] tables8, char[][] tables16) {
        this.tables8 = tables8;
        this.tables16 = tables16;
    }

    /**
     * Composes the curves of a pipeline made only of curve stages.
     *
     * @param pipeline a pipeline made only of curve stages
     * @return one composed curve per channel
     */
    static ToneCurve[] compose(Pipeline pipeline) {
        var channels = pipeline.inputs();
        var samples = new int[channels][POINTS];
        var in = new float[channels];
        var out = new float[channels];
        for (var i = 0; i < POINTS; i++) {
            for (var c = 0; c < channels; c++) {
                in[c] = (float) ((double) i / (POINTS - 1));
            }
            pipeline.evaluate(in, out);
            for (var c = 0; c < channels; c++) {
                samples[c][i] = ColorMath.saturateWord(out[c] * 65535.0);
            }
        }
        var curves = new ToneCurve[channels];
        for (var c = 0; c < channels; c++) {
            curves[c] = ToneCurve.tabulated(samples[c]);
        }
        return curves;
    }

    /**
     * Returns whether every composed curve is the identity within the Little CMS tolerance.
     *
     * @param curves the composed curves, one per channel
     * @return {@code true} if all are linear
     */
    static boolean allLinear(ToneCurve[] curves) {
        for (var curve : curves) {
            if (!curve.isIdentity()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Creates an engine for 8-bit samples.
     *
     * @param curves the composed curves
     * @return the engine
     */
    static CurvesEngine eightBit(ToneCurve[] curves) {
        var tables = new byte[curves.length][256];
        for (var c = 0; c < curves.length; c++) {
            for (var v = 0; v < 256; v++) {
                tables[c][v] = (byte) ColorMath.wordToByte(curves[c].evaluate16((v << 8) | v));
            }
        }
        return new CurvesEngine(tables, null);
    }

    /**
     * Creates an engine for 16-bit samples.
     *
     * @param curves the composed curves
     * @return the engine
     */
    static CurvesEngine sixteenBit(ToneCurve[] curves) {
        var tables = new char[curves.length][65536];
        for (var c = 0; c < curves.length; c++) {
            for (var v = 0; v < 65536; v++) {
                tables[c][v] = (char) curves[c].evaluate16(v);
            }
        }
        return new CurvesEngine(null, tables);
    }

    @Override
    public void apply(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int count) {
        var tables = tables8;
        var channels = tables.length;
        for (var i = 0; i < count; i++) {
            var s = srcOffset + i * srcStride;
            var d = dstOffset + i * dstStride;
            for (var c = 0; c < channels; c++) {
                dst[d + c] = tables[c][src[s + c] & 0xFF];
            }
        }
    }

    @Override
    public void apply(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride, int count) {
        var tables = tables16;
        var channels = tables.length;
        for (var i = 0; i < count; i++) {
            var s = srcOffset + i * srcStride;
            var d = dstOffset + i * dstStride;
            for (var c = 0; c < channels; c++) {
                dst[d + c] = (short) tables[c][src[s + c] & 0xFFFF];
            }
        }
    }
}
