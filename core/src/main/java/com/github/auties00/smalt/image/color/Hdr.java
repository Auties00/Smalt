package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.TransferCharacteristics;

import java.util.Optional;

/**
 * Holds the CICP color primaries table and the high-dynamic-range transfer functions, tables and tone
 * mapping of color management.
 *
 * <p>The perceptual quantizer (PQ) is SMPTE ST 2084 and hybrid log-gamma (HLG) is ARIB STD-B67,
 * both as ITU-R BT.2100 specifies them; HLG display light is that of the BT.2100 reference display
 * of 1000 candelas per square meter with a black of zero (system gamma 1.2). The tone mapping is the
 * electrical-electrical transfer function of ITU-R BT.2390 (section 5.4), applied in the PQ domain
 * with a source black and a target black of zero.
 */
final class Hdr {
    /**
     * The PQ constant {@code m1}.
     */
    private static final double M1 = 2610.0 / 16384.0;

    /**
     * The PQ constant {@code m2}.
     */
    private static final double M2 = 2523.0 / 4096.0 * 128.0;

    /**
     * The PQ constant {@code c1}.
     */
    private static final double C1 = 3424.0 / 4096.0;

    /**
     * The PQ constant {@code c2}.
     */
    private static final double C2 = 2413.0 / 4096.0 * 32.0;

    /**
     * The PQ constant {@code c3}.
     */
    private static final double C3 = 2392.0 / 4096.0 * 32.0;

    /**
     * The HLG constant {@code a}.
     */
    private static final double HLG_A = 0.17883277;

    /**
     * The HLG constant {@code b}.
     */
    private static final double HLG_B = 1 - 4 * HLG_A;

    /**
     * The HLG constant {@code c}.
     */
    private static final double HLG_C = 0.5 - HLG_A * StrictMath.log(4 * HLG_A);

    /**
     * The peak luminance of the PQ signal range, in candelas per square meter.
     */
    static final double PQ_PEAK = 10000.0;

    /**
     * The nominal peak luminance of the HLG reference display, in candelas per square meter.
     */
    static final double HLG_PEAK = 1000.0;

    /**
     * The HLG system gamma of the 1000 candelas per square meter reference display.
     */
    static final double HLG_GAMMA = 1.2;

    /**
     * The reference white of ITU-R BT.2408, in candelas per square meter: the luminance of a
     * standard-dynamic-range white placed in a high-dynamic-range signal.
     */
    static final double REFERENCE_WHITE = 203.0;

    /**
     * The number of steps of the square-root-indexed and fourth-root-indexed tables.
     */
    static final int STEPS = 4096;

    /**
     * The number of entries of the tone curves of the ICC form of a high-dynamic-range profile.
     */
    static final int ICC_CURVE_ENTRIES = 4096;

    /**
     * Prevents instantiation.
     */
    private Hdr() {
        throw new AssertionError();
    }

    /**
     * Returns the chromaticities of CICP color primaries.
     *
     * @param primaries the primaries, {@link ColorPrimaries#UNSPECIFIED} meaning BT.709
     * @return red, green, blue and white as {@code {x, y}} pairs
     */
    static double[][] chromaticities(ColorPrimaries primaries) {
        var defined = primaries == ColorPrimaries.UNSPECIFIED ? ColorPrimaries.BT709 : primaries;
        return new double[][]{xy(defined.red()), xy(defined.green()), xy(defined.blue()), xy(defined.white())};
    }

    /**
     * Converts a defined chromaticity to an {@code {x, y}} pair.
     *
     * @param chromaticity the chromaticity, present
     * @return the pair
     */
    private static double[] xy(Optional<Chromaticity> chromaticity) {
        var value = chromaticity.orElseThrow();
        return new double[]{value.x(), value.y()};
    }

    /**
     * Converts a PQ signal to luminance.
     *
     * @param signal the signal, {@code 0..1}
     * @return the luminance in candelas per square meter
     */
    static double pqToNits(double signal) {
        if (!(signal > 0)) {
            return 0;
        }
        var p = StrictMath.pow(Math.min(signal, 1.0), 1.0 / M2);
        var numerator = Math.max(p - C1, 0);
        return PQ_PEAK * StrictMath.pow(numerator / (C2 - C3 * p), 1.0 / M1);
    }

    /**
     * Converts luminance to a PQ signal.
     *
     * @param nits the luminance in candelas per square meter
     * @return the signal, {@code 0..1}
     */
    static double nitsToPq(double nits) {
        if (!(nits > 0)) {
            return 0;
        }
        var y = StrictMath.pow(Math.min(nits / PQ_PEAK, 1.0), M1);
        return StrictMath.pow((C1 + C2 * y) / (1 + C3 * y), M2);
    }

    /**
     * Converts an HLG signal to normalized scene light (the inverse OETF).
     *
     * @param signal the signal, {@code 0..1}
     * @return the scene light, {@code 0..1}
     */
    static double hlgToScene(double signal) {
        if (!(signal > 0)) {
            return 0;
        }
        if (signal <= 0.5) {
            return signal * signal / 3.0;
        }
        return (StrictMath.exp((Math.min(signal, 1.0) - HLG_C) / HLG_A) + HLG_B) / 12.0;
    }

    /**
     * Converts normalized scene light to an HLG signal (the OETF).
     *
     * @param scene the scene light, {@code 0..1}
     * @return the signal, {@code 0..1}
     */
    static double hlgOetf(double scene) {
        if (!(scene > 0)) {
            return 0;
        }
        var e = Math.min(scene, 1.0);
        if (e <= 1.0 / 12.0) {
            return Math.sqrt(3 * e);
        }
        return HLG_A * StrictMath.log(12 * e - HLG_B) + HLG_C;
    }

    /**
     * Returns the HLG system gamma of a display.
     *
     * @param peak the nominal peak luminance of the display in candelas per square meter
     * @return the gamma, {@code 1.2} at 1000 candelas per square meter
     */
    static double hlgGamma(double peak) {
        return 1.2 + 0.42 * StrictMath.log10(peak / 1000.0);
    }

    /**
     * Applies the BT.2390 tone mapping to a luminance.
     *
     * @param nits the luminance of the source in candelas per square meter
     * @param sourcePeak the peak luminance of the source
     * @param targetPeak the peak luminance of the target
     * @return the mapped luminance, at most {@code targetPeak}
     */
    static double eetf(double nits, double sourcePeak, double targetPeak) {
        if (sourcePeak <= targetPeak) {
            return Math.min(nits, targetPeak);
        }
        var sourceSignal = nitsToPq(sourcePeak);
        var e1 = nitsToPq(Math.min(nits, sourcePeak)) / sourceSignal;
        var maxLum = nitsToPq(targetPeak) / sourceSignal;
        var ks = 1.5 * maxLum - 0.5;
        double e2;
        if (e1 < ks) {
            e2 = e1;
        } else {
            var t = (e1 - ks) / (1 - ks);
            var t2 = t * t;
            var t3 = t2 * t;
            e2 = (2 * t3 - 3 * t2 + 1) * ks + (t3 - 2 * t2 + t) * (1 - ks) + (-2 * t3 + 3 * t2) * maxLum;
        }
        return Math.min(pqToNits(e2 * sourceSignal), targetPeak);
    }

    /**
     * Returns the linear RGB to XYZ matrix of a set of primaries relative to their own white.
     *
     * @param red the red chromaticity
     * @param green the green chromaticity
     * @param blue the blue chromaticity
     * @param white the white chromaticity
     * @return the matrix in row-major order; its middle row holds the luminance weights
     */
    static double[] rgbToXyz(double[] red, double[] green, double[] blue, double[] white) {
        var primaries = new double[]{
                red[0], green[0], blue[0],
                red[1], green[1], blue[1],
                1 - red[0] - red[1], 1 - green[0] - green[1], 1 - blue[0] - blue[1]
        };
        var coefficients = ColorMath.apply(ColorMath.invert(primaries), ColorMath.xyToXyz(white[0], white[1]));
        var matrix = new double[9];
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < 3; column++) {
                matrix[row * 3 + column] = coefficients[column] * primaries[row * 3 + column];
            }
        }
        return matrix;
    }

    /**
     * Returns the linear RGB to XYZ matrix of the primaries of a high-dynamic-range profile.
     *
     * @param spec the profile description
     * @return the matrix in row-major order
     */
    static double[] rgbToXyz(ColorProfile.HdrSpec spec) {
        return rgbToXyz(spec.red(), spec.green(), spec.blue(), spec.white());
    }

    /**
     * Returns the matrix that converts linear light from the primaries of one high-dynamic-range
     * profile to those of another, adapting the white with the Bradford transform when the two
     * whites differ.
     *
     * @param source the profile the light is in
     * @param target the profile to convert it to
     * @return the matrix in row-major order
     */
    static double[] gamutMatrix(ColorProfile.HdrSpec source, ColorProfile.HdrSpec target) {
        var toXyz = rgbToXyz(source);
        var fromXyz = ColorMath.invert(rgbToXyz(target));
        var adaptation = ColorMath.bradford(ColorMath.xyToXyz(source.white()[0], source.white()[1]),
                ColorMath.xyToXyz(target.white()[0], target.white()[1]));
        var adapted = adaptation == null ? toXyz : ColorMath.multiply(adaptation, toXyz);
        return ColorMath.multiply(fromXyz, adapted);
    }

    /**
     * Returns the value of the tone curves of the ICC form of a high-dynamic-range profile, the
     * rendering of a reader that ignores the profile's {@code cicp} tag.
     *
     * <p>The signal is decoded to display light channel by channel (the HLG OOTF applied to each
     * channel, which is exact for neutral colors), compressed from 1000 to 203 candelas per square
     * meter with the BT.2390 EETF and divided by 203, so that neutral colors render as the default
     * options of a {@link ColorTransform} render them.
     *
     * @param transfer {@link TransferCharacteristics#PQ} or {@link TransferCharacteristics#HLG}
     * @param signal the signal, {@code 0..1}
     * @return the relative linear light, {@code 0..1}
     */
    static double iccCurve(TransferCharacteristics transfer, double signal) {
        var nits = transfer == TransferCharacteristics.PQ ? pqToNits(signal)
                : HLG_PEAK * StrictMath.pow(hlgToScene(signal), HLG_GAMMA);
        return Math.min(eetf(nits, HLG_PEAK, REFERENCE_WHITE) / REFERENCE_WHITE, 1.0);
    }

    /**
     * Interpolates a table indexed by the square root of its argument.
     *
     * @param table {@link #STEPS} {@code + 2} samples, the last one repeated
     * @param x the argument, clamped to {@code 0..1}
     * @return the interpolated value
     */
    static float lookupSqrt(float[] table, float x) {
        if (!(x > 0f)) {
            return table[0];
        }
        if (x >= 1f) {
            return table[STEPS];
        }
        var position = (float) Math.sqrt(x) * STEPS;
        var index = (int) position;
        var low = table[index];
        return low + (table[index + 1] - low) * (position - index);
    }

    /**
     * Interpolates a table indexed by the fourth root of its argument.
     *
     * @param table {@link #STEPS} {@code + 2} samples, the last one repeated
     * @param x the argument, clamped to {@code 0..1}
     * @return the interpolated value
     */
    static float lookupFourthRoot(float[] table, float x) {
        if (!(x > 0f)) {
            return table[0];
        }
        if (x >= 1f) {
            return table[STEPS];
        }
        var position = (float) Math.sqrt(Math.sqrt(x)) * STEPS;
        var index = (int) position;
        var low = table[index];
        return low + (table[index + 1] - low) * (position - index);
    }

    /**
     * Holds the transfer function tables, each computed on first use.
     */
    static final class Tables {
        /**
         * Prevents instantiation.
         */
        private Tables() {
            throw new AssertionError();
        }

        /**
         * {@return the luminance of every 16-bit PQ sample, in candelas per square meter}
         */
        static float[] pqDecode() {
            return PqDecode.TABLE;
        }

        /**
         * {@return the normalized scene light of every 16-bit HLG sample}
         */
        static float[] hlgDecode() {
            return HlgDecode.TABLE;
        }

        /**
         * {@return the PQ signal at a luminance of {@code (k / STEPS)^4} times 10000 candelas per square meter}
         */
        static float[] pqEncode() {
            return PqEncode.TABLE;
        }

        /**
         * {@return the HLG signal at a normalized scene light of {@code (k / STEPS)^2}}
         */
        static float[] hlgEncode() {
            return HlgEncode.TABLE;
        }

        /**
         * {@return the HLG OOTF factor {@code 1000 * Ys^(gamma - 1)} of the 1000 candelas per square meter
         * reference display at a normalized scene luminance {@code Ys} of {@code (k / STEPS)^2}}
         */
        static float[] hlgOotf() {
            return HlgOotf.TABLE;
        }

        /**
         * Holds the HLG OOTF table.
         */
        private static final class HlgOotf {
            /**
             * The table of {@code STEPS + 2} entries.
             */
            static final float[] TABLE = ootf();
        }

        /**
         * Computes the HLG OOTF table.
         *
         * @return the table of {@code STEPS + 2} entries
         */
        private static float[] ootf() {
            var gamma = hlgGamma(HLG_PEAK);
            var table = new float[STEPS + 2];
            for (var k = 0; k <= STEPS; k++) {
                var ys = StrictMath.pow((double) k / STEPS, 2);
                table[k] = (float) (HLG_PEAK * StrictMath.pow(ys, gamma - 1));
            }
            table[STEPS + 1] = table[STEPS];
            return table;
        }

        /**
         * Holds the PQ decoding table.
         */
        private static final class PqDecode {
            /**
             * The table of 65536 entries.
             */
            static final float[] TABLE = decode(false);
        }

        /**
         * Holds the HLG decoding table.
         */
        private static final class HlgDecode {
            /**
             * The table of 65536 entries.
             */
            static final float[] TABLE = decode(true);
        }

        /**
         * Holds the PQ encoding table.
         */
        private static final class PqEncode {
            /**
             * The table of {@code STEPS + 2} entries.
             */
            static final float[] TABLE = encode(false);
        }

        /**
         * Holds the HLG encoding table.
         */
        private static final class HlgEncode {
            /**
             * The table of {@code STEPS + 2} entries.
             */
            static final float[] TABLE = encode(true);
        }

        /**
         * Computes a decoding table.
         *
         * @param hlg whether to decode HLG rather than PQ
         * @return the table of 65536 entries
         */
        private static float[] decode(boolean hlg) {
            var table = new float[65536];
            for (var v = 0; v < 65536; v++) {
                var signal = v / 65535.0;
                table[v] = (float) (hlg ? hlgToScene(signal) : pqToNits(signal));
            }
            return table;
        }

        /**
         * Computes an encoding table.
         *
         * @param hlg whether to encode HLG, indexed by the square root of scene light, rather than PQ,
         * indexed by the fourth root of luminance over 10000 candelas per square meter
         * @return the table of {@code STEPS + 2} entries
         */
        private static float[] encode(boolean hlg) {
            var table = new float[STEPS + 2];
            for (var k = 0; k <= STEPS; k++) {
                var x = (double) k / STEPS;
                table[k] = (float) (hlg ? hlgOetf(x * x) : nitsToPq(x * x * x * x * PQ_PEAK));
            }
            table[STEPS + 1] = table[STEPS];
            return table;
        }
    }
}
