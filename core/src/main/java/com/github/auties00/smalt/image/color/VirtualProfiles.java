/* Ports Little CMS 2.17 (cmsvirt.c, cmswtpnt.c, cmspcs.c). Copyright Marti Maria Saguer; MIT License.
 * Modified for Smalt. */
package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Builds the ICC profiles that hold computed lookup tables: ink limiting device links, abstract L*a*b* adjustment
 * profiles and device links sampled from a transform.
 *
 * @implNote This implementation follows Little CMS 2.17's {@code cmsCreateInkLimitingDeviceLink},
 * {@code cmsCreateBCHSWabstractProfile} and {@code cmsTransform2DeviceLink} ({@code cmsvirt.c}) with
 * {@code cmsFLAGS_FORCE_CLUT}, {@code cmsWhitePointFromTemp} ({@code cmswtpnt.c}) and the L*a*b* and LCh conversions of
 * {@code cmspcs.c}. Every profile is written as version 4.3 with a {@code lutAtoBType} between identity curves.
 */
final class VirtualProfiles {
    /**
     * The grid points per channel of an ink limiting table.
     */
    private static final int INK_LIMIT_POINTS = 17;

    /**
     * The largest number of samples of a device link's lookup table, the most a profile may hold to be read back.
     */
    private static final int MAX_LINK_SAMPLES = 1 << 22;

    /**
     * Prevents instantiation.
     */
    private VirtualProfiles() {
        throw new AssertionError();
    }

    /**
     * Returns the 16-bit coordinate of a grid point ({@code _cmsQuantizeVal}).
     *
     * @param index the grid point index
     * @param points the number of grid points
     * @return the coordinate
     */
    private static int quantize(int index, int points) {
        return ColorMath.saturateWord(index * 65535.0 / (points - 1));
    }

    /**
     * Writes a CMYK device link that limits the total ink.
     *
     * @param limit the largest sum of the four inks, in percent, from 1 to 400
     * @return the profile bytes
     */
    static byte[] inkLimiting(double limit) {
        var points = INK_LIMIT_POINTS;
        var nodes = points * points * points * points;
        var table = new int[nodes * 4];
        var inkLimit = limit * 655.35;
        var in = new int[4];
        for (var node = 0; node < nodes; node++) {
            var rest = node;
            for (var t = 3; t >= 0; --t) {
                in[t] = quantize(rest % points, points);
                rest /= points;
            }
            var sumCmy = (double) in[0] + in[1] + in[2];
            var sumCmyk = sumCmy + in[3];
            double ratio;
            if (sumCmyk > inkLimit) {
                ratio = 1 - ((sumCmyk - inkLimit) / sumCmy);
                if (ratio < 0) {
                    ratio = 0;
                }
            } else {
                ratio = 1;
            }
            table[node * 4] = ColorMath.saturateWord(in[0] * ratio);
            table[node * 4 + 1] = ColorMath.saturateWord(in[1] * ratio);
            table[node * 4 + 2] = ColorMath.saturateWord(in[2] * ratio);
            table[node * 4 + 3] = in[3];
        }
        var grid = new int[]{points, points, points, points};
        return IccWriter.table("Ink limit " + limit + "%", "link", "CMYK", "CMYK", grid, 4, table, 0, null);
    }

    /**
     * Writes an abstract L*a*b* profile that adjusts lightness, contrast, hue, chroma and the white balance.
     *
     * @param adjustments the lightness, hue, chroma and white balance changes
     * @return the profile bytes
     */
    static byte[] labAdjustment(LabAdjustments adjustments) {
        var points = adjustments.gridPoints();
        var nodes = points * points * points;
        var table = new int[nodes * 3];
        var balance = adjustments.sourceTemperature() != adjustments.targetTemperature();
        var source = balance ? whiteFromTemperature(adjustments.sourceTemperature()) : null;
        var target = balance ? whiteFromTemperature(adjustments.targetTemperature()) : null;
        var bands = ParallelBands.bands(nodes, 64L * nodes);
        ParallelBands.run(bands, band -> {
            var lab = new double[3];
            var end = ParallelBands.start(band + 1, bands, nodes);
            for (var node = ParallelBands.start(band, bands, nodes); node < end; node++) {
                var l = quantize(node / (points * points), points);
                var a = quantize(node / points % points, points);
                var b = quantize(node % points, points);
                var inL = l / 655.35;
                var inA = a / 257.0 - 128.0;
                var inB = b / 257.0 - 128.0;
                var chroma = Math.sqrt(inA * inA + inB * inB);
                var hue = degrees(inB, inA);
                var outL = inL * adjustments.contrast() + adjustments.brightness();
                var outC = chroma + adjustments.saturation();
                var outH = (hue + adjustments.hue()) * Math.PI / 180.0;
                lab[0] = outL;
                lab[1] = outC * StrictMath.cos(outH);
                lab[2] = outC * StrictMath.sin(outH);
                if (balance) {
                    var xyz = labToXyz(source, lab);
                    xyzToLab(target, xyz, lab);
                }
                encodeLab(lab, table, node * 3);
            }
        });
        var grid = new int[]{points, points, points};
        return IccWriter.table("L*a*b* adjustment", "abst", "Lab ", "Lab ", grid, 3, table, 0, ColorMath.d50());
    }

    /**
     * Returns the angle of a vector in degrees, in {@code 0..360}, zero for the null vector ({@code atan2deg}).
     *
     * @param y the second coordinate
     * @param x the first coordinate
     * @return the angle
     */
    private static double degrees(double y, double x) {
        var h = x == 0 && y == 0 ? 0 : StrictMath.atan2(y, x);
        h *= 180.0 / Math.PI;
        while (h > 360.0) {
            h -= 360.0;
        }
        while (h < 0) {
            h += 360.0;
        }
        return h;
    }

    /**
     * Returns the white of a correlated color temperature on the CIE daylight locus ({@code cmsWhitePointFromTemp}).
     *
     * @param kelvins the temperature, from 4000 to 25000
     * @return the white, XYZ with {@code Y = 1}
     */
    static double[] whiteFromTemperature(int kelvins) {
        double t = kelvins;
        var t2 = t * t;
        var t3 = t2 * t;
        double x;
        if (t >= 4000.0 && t <= 7000.0) {
            x = -4.6070 * (1E9 / t3) + 2.9678 * (1E6 / t2) + 0.09911 * (1E3 / t) + 0.244063;
        } else {
            x = -2.0064 * (1E9 / t3) + 1.9018 * (1E6 / t2) + 0.24748 * (1E3 / t) + 0.237040;
        }
        var y = -3.000 * (x * x) + 2.870 * x - 0.275;
        return new double[]{(x / y) * 1.0, 1.0, ((1 - x - y) / y) * 1.0};
    }

    /**
     * Converts L*a*b* relative to a white to XYZ ({@code cmsLab2XYZ}).
     *
     * @param white the reference white, XYZ
     * @param lab the L*a*b* value
     * @return the XYZ value
     */
    private static double[] labToXyz(double[] white, double[] lab) {
        var y = (lab[0] + 16.0) / 116.0;
        var x = y + 0.002 * lab[1];
        var z = y - 0.005 * lab[2];
        return new double[]{ColorMath.labInverse(x) * white[0], ColorMath.labInverse(y) * white[1],
                ColorMath.labInverse(z) * white[2]};
    }

    /**
     * Converts XYZ to L*a*b* relative to a white ({@code cmsXYZ2Lab}).
     *
     * @param white the reference white, XYZ
     * @param xyz the XYZ value
     * @param lab receives the L*a*b* value
     */
    private static void xyzToLab(double[] white, double[] xyz, double[] lab) {
        var fx = ColorMath.labForward(xyz[0] / white[0]);
        var fy = ColorMath.labForward(xyz[1] / white[1]);
        var fz = ColorMath.labForward(xyz[2] / white[2]);
        lab[0] = 116.0 * fy - 16.0;
        lab[1] = 500.0 * (fx - fy);
        lab[2] = 200.0 * (fy - fz);
    }

    /**
     * Encodes L*a*b* in the version 4 16-bit encoding, clamped to its range ({@code cmsFloat2LabEncoded}).
     *
     * @param lab the L*a*b* value
     * @param out receives the three samples
     * @param at the index of the first sample
     */
    private static void encodeLab(double[] lab, int[] out, int at) {
        var l = Math.clamp(lab[0], 0.0, 100.0);
        var a = Math.clamp(lab[1], -128.0, 127.0);
        var b = Math.clamp(lab[2], -128.0, 127.0);
        out[at] = ColorMath.saturateWord(l * 655.35);
        out[at + 1] = ColorMath.saturateWord((a + 128.0) * 257.0);
        out[at + 2] = ColorMath.saturateWord((b + 128.0) * 257.0);
    }

    /**
     * Returns the number of samples of a lookup table.
     *
     * @param points the grid points per input
     * @param inputs the number of inputs
     * @param outputs the number of outputs
     * @return the samples, saturated at {@link Long#MAX_VALUE} divided by the grid points
     */
    private static long samples(int points, int inputs, int outputs) {
        long total = outputs;
        for (var i = 0; i < inputs && total <= Long.MAX_VALUE / points; i++) {
            total *= points;
        }
        return total;
    }

    /**
     * Writes the device link of a transform: a lookup table sampled from its 16-bit conversion, or, for a transform
     * from a named color profile, a named color profile of the transform's outputs. The gamut check of a proofing
     * transform is not sampled: the link holds the simulation of the proofing device alone.
     *
     * @param link the transform to sample
     * @return the profile bytes
     */
    static byte[] deviceLink(ColorTransform link) {
        var transform = withoutGamutCheck(link);
        var entry = IccReader.name(transform.entrySpace());
        var exit = IccReader.name(transform.exitSpace());
        var intent = transform.lastIntent().code();
        var source = transform.source();
        if (transform.isNamed()) {
            var named = source.icc().namedColors();
            var count = named.roots().length;
            var outputs = transform.outputChannels();
            var indexes = new short[count];
            for (var i = 0; i < count; i++) {
                indexes[i] = (short) i;
            }
            var out = new short[count * outputs];
            transform.transform(indexes, 0, 1, out, 0, outputs, count);
            var device = new int[out.length];
            for (var i = 0; i < out.length; i++) {
                device[i] = out[i] & 0xFFFF;
            }
            return IccWriter.namedColors("Named color device link", exit, IccReader.name(source.icc().pcs),
                    named.prefix(), named.suffix(), named.roots(), named.pcs(), outputs, device, intent);
        }
        var inputs = transform.inputChannels();
        var outputs = transform.outputChannels();
        var points = inputs > 4 ? 7 : inputs == 4 ? 17 : 33;
        while (points > 2 && samples(points, inputs, outputs) > MAX_LINK_SAMPLES) {
            points--;
        }
        var nodes = 1;
        for (var i = 0; i < inputs; i++) {
            nodes *= points;
        }
        var in = new short[nodes * inputs];
        for (var node = 0; node < nodes; node++) {
            var rest = node;
            for (var t = inputs - 1; t >= 0; --t) {
                in[node * inputs + t] = (short) quantize(rest % points, points);
                rest /= points;
            }
        }
        var out = new short[nodes * outputs];
        transform.transform(in, 0, inputs, out, 0, outputs, nodes);
        var table = new int[out.length];
        for (var i = 0; i < out.length; i++) {
            table[i] = out[i] & 0xFFFF;
        }
        var grid = new int[inputs];
        Arrays.fill(grid, points);
        var white = transform.target().icc().xyz(IccReader.signature("wtpt"));
        return IccWriter.table("Device link", "link", entry, exit, grid, outputs, table, intent,
                white == null ? ColorMath.d50() : white);
    }

    /**
     * Returns a transform without the gamut check of a proofing transform, as {@code cmsTransform2DeviceLink} samples
     * the transform's pipeline alone.
     *
     * @param transform the transform to sample, possibly a proofing one with a gamut check
     * @return the same proofing transform without its gamut check, or {@code transform} itself when it has none
     */
    private static ColorTransform withoutGamutCheck(ColorTransform transform) {
        var proofing = transform.proofingOptions().orElse(null);
        if (proofing == null || !proofing.gamutCheck()) {
            return transform;
        }
        return ColorTransform.createProofing(transform.source(), transform.proof().orElseThrow(), transform.target(),
                transform.options(), proofing.toBuilder().gamutCheck(false).build());
    }
}
