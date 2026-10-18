package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorPrimaries;

import java.util.Optional;

/**
 * Holds the CICP color primaries table and the high-dynamic-range transfer functions and tone
 * mapping of color management.
 *
 * <p>The perceptual quantizer (PQ) is SMPTE ST 2084 and hybrid log-gamma (HLG) is ARIB STD-B67,
 * both as ITU-R BT.2100 specifies them. The tone mapping is the electrical-electrical transfer
 * function of ITU-R BT.2390 (section 5.4), applied in the PQ domain with a source black and a
 * target black of zero.
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
    private static final double HLG_C = 0.5 - HLG_A * Math.log(4 * HLG_A);

    /**
     * The peak luminance of the PQ signal range, in candelas per square meter.
     */
    static final double PQ_PEAK = 10000.0;

    /**
     * The nominal peak luminance of the HLG reference display, in candelas per square meter.
     */
    static final double HLG_PEAK = 1000.0;

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
     * @throws MediaException.Unsupported for primaries without a chromaticity definition
     */
    static double[][] chromaticities(ColorPrimaries primaries) {
        var defined = primaries == ColorPrimaries.UNSPECIFIED ? ColorPrimaries.BT709 : primaries;
        if (defined.white().isEmpty()) {
            throw new MediaException.Unsupported("cicp", "CIE XYZ (SMPTE 428) color primaries");
        }
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
        var p = Math.pow(Math.min(signal, 1.0), 1.0 / M2);
        var numerator = Math.max(p - C1, 0);
        return PQ_PEAK * Math.pow(numerator / (C2 - C3 * p), 1.0 / M1);
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
        var y = Math.pow(Math.min(nits / PQ_PEAK, 1.0), M1);
        return Math.pow((C1 + C2 * y) / (1 + C3 * y), M2);
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
        return (Math.exp((Math.min(signal, 1.0) - HLG_C) / HLG_A) + HLG_B) / 12.0;
    }

    /**
     * Returns the HLG system gamma of a display.
     *
     * @param peak the nominal peak luminance of the display in candelas per square meter
     * @return the gamma, {@code 1.2} at 1000 candelas per square meter
     */
    static double hlgGamma(double peak) {
        return 1.2 + 0.42 * Math.log10(peak / 1000.0);
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
}
