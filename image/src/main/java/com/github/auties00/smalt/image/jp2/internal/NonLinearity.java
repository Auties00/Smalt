/* Ports OpenJPH 0.32.0 (src/core/codestream/ojph_params.cpp, src/core/transform/ojph_colour.cpp: the NLT type 4).
   Copyright (c) 2019, Aous Naman; Copyright (c) 2019, Kakadu Software Pty Ltd, Australia; Copyright (c) 2019, The
   University of New South Wales, Australia; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Holds the non-linear point transformation of an {@code NLT} segment (ISO/IEC 15444-2 annex K) and applies it to
 * the samples of a reconstructed image component.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param type the transformation: 0 for none, 1 gamma-style, 2 LUT-style, 3 two's complement to sign-magnitude, 4
 * LUT-style followed by the two's complement to sign-magnitude conversion
 * @param precision the bit depth of the decoded component, from 1 to 38
 * @param signed whether the decoded component is signed
 * @param parameters the gamma-style parameters {@code E}, {@code S}, {@code T}, {@code A} and {@code B}, or the
 * LUT-style {@code Dmin} and {@code Dmax}; empty for the other types
 * @param table the normalized LUT-style table values {@code t_k}, or {@code null}
 * @implNote This implementation also reads type 4, which is not part of ISO/IEC 15444-2: OpenJPH's experimental
 * {@code OJPH_NLT_BINARY_COMPLEMENT_PLUS_LUT}, which OpenEXR's LJ2K compression writes. Its segment has the layout
 * of the LUT-style one, its table values are scaled by {@code 2^BDnlt} rather than {@code 2^PTval - 1}, and it
 * applies, as in OpenJPH, to the unrounded samples of an irreversible component.
 */
record NonLinearity(int type, int precision, boolean signed, double[] parameters, double[] table) {
    /**
     * The transformation that applies nothing.
     */
    static final int NONE = 0;

    /**
     * The gamma-style transformation.
     */
    static final int GAMMA = 1;

    /**
     * The LUT-style transformation.
     */
    static final int LOOKUP = 2;

    /**
     * The two's complement to sign-magnitude conversion.
     */
    static final int SIGN_MAGNITUDE = 3;

    /**
     * The LUT-style transformation followed by the two's complement to sign-magnitude conversion.
     */
    static final int SIGN_MAGNITUDE_LOOKUP = 4;

    /**
     * Holds a parsed {@code NLT} segment.
     *
     * @param component the component it applies to, or 65535 for every component
     * @param transformation the transformation the segment defines
     */
    record Indexed(int component, NonLinearity transformation) {
    }

    /**
     * Parses an {@code NLT} segment (A.3.10).
     *
     * @param data the codestream
     * @param position the index of its {@code Cnlt} field
     * @param end the end of the segment
     * @return the transformation and its component
     * @throws MediaException.Malformed if the segment is truncated or a value is out of range
     */
    static Indexed parse(byte[] data, int position, int end) {
        if (end - position < 4) {
            throw new MediaException.Malformed("truncated JPEG 2000 NLT segment");
        }
        var component = ByteArrays.u16be(data, position);
        var depth = data[position + 2] & 0xFF;
        var precision = (depth & 0x7F) + 1;
        if (precision > 38) {
            throw new MediaException.Malformed("invalid JPEG 2000 NLT bit depth " + precision);
        }
        var type = data[position + 3] & 0xFF;
        var at = position + 4;
        double[] parameters = new double[0];
        double[] table = null;
        switch (type) {
            case NONE, SIGN_MAGNITUDE -> {
            }
            case GAMMA -> {
                if (end - at < 15) {
                    throw new MediaException.Malformed("truncated JPEG 2000 NLT segment");
                }
                parameters = new double[5];
                for (var i = 0; i < 5; i++) {
                    parameters[i] = (data[at + 3 * i] & 0xFF) + ByteArrays.u16be(data, at + 3 * i + 1) / 65535.0;
                }
                if (parameters[0] == 0 || parameters[1] == 0 || parameters[3] == 0) {
                    throw new MediaException.Malformed("JPEG 2000 NLT gamma with a zero E, S or A parameter");
                }
            }
            case LOOKUP, SIGN_MAGNITUDE_LOOKUP -> {
                if (end - at < 11) {
                    throw new MediaException.Malformed("truncated JPEG 2000 NLT segment");
                }
                var points = ByteArrays.u16be(data, at) + 1;
                var dmin = ByteArrays.u32be(data, at + 2) / 4294967295.0;
                var dmax = ByteArrays.u32be(data, at + 6) / 4294967295.0;
                var bits = data[at + 10] & 0xFF;
                if (points < 2 || bits < 1 || bits > 32 || dmax <= dmin) {
                    throw new MediaException.Malformed("invalid JPEG 2000 NLT lookup table");
                }
                var size = bits <= 8 ? 1 : bits <= 16 ? 2 : 4;
                at += 11;
                if ((long) end - at < (long) points * size) {
                    throw new MediaException.Malformed("truncated JPEG 2000 NLT lookup table");
                }
                var scale = type == LOOKUP ? (double) ((1L << bits) - 1) : Math.scalb(1.0, precision);
                table = new double[points];
                for (var k = 0; k < points; k++) {
                    var value = switch (size) {
                        case 1 -> data[at] & 0xFF;
                        case 2 -> ByteArrays.u16be(data, at);
                        default -> ByteArrays.u32be(data, at);
                    };
                    table[k] = value / scale;
                    at += size;
                }
                parameters = new double[]{dmin, dmax};
            }
            default -> throw new MediaException.Malformed("invalid JPEG 2000 NLT type " + type);
        }
        return new Indexed(component, new NonLinearity(type, precision, (depth & 0x80) != 0, parameters, table));
    }

    /**
     * Applies the transformation to the samples of a reconstructed image component, in place.
     *
     * <p>The results are rounded to the nearest integer, ties away from zero, and clamped to the range of the decoded
     * component.
     *
     * @param samples the samples, within the range of {@code inputPrecision} and {@code inputSigned}
     * @param inputPrecision the bit depth of the reconstructed component
     * @param inputSigned whether the reconstructed component is signed
     */
    void apply(long[] samples, int inputPrecision, boolean inputSigned) {
        var outputMin = signed ? -(1L << (precision - 1)) : 0;
        var outputMax = signed ? (1L << (precision - 1)) - 1 : (1L << precision) - 1;
        switch (type) {
            case GAMMA -> gamma(samples, inputPrecision, outputMin, outputMax);
            case LOOKUP -> lookup(samples, inputPrecision, inputSigned, outputMin, outputMax);
            case SIGN_MAGNITUDE_LOOKUP -> {
                var bias = inputSigned ? Math.scalb(1.0, inputPrecision - 1) : 0;
                var scale = Math.scalb(1.0, -inputPrecision);
                for (var i = 0; i < samples.length; i++) {
                    samples[i] = signMagnitudeLookup((samples[i] + bias) * scale, outputMin, outputMax);
                }
            }
            case SIGN_MAGNITUDE -> {
                var limit = 1L << (inputPrecision - 1);
                for (var i = 0; i < samples.length; i++) {
                    var value = samples[i];
                    if (value < 0) {
                        value = Math.min(-limit - value - 1, -1);
                    }
                    samples[i] = Math.clamp(value, outputMin, outputMax);
                }
            }
            default -> {
                for (var i = 0; i < samples.length; i++) {
                    samples[i] = Math.clamp(samples[i], outputMin, outputMax);
                }
            }
        }
    }

    /**
     * Applies the transformation of type {@link #SIGN_MAGNITUDE_LOOKUP} to the unrounded samples of an irreversible
     * component.
     *
     * @param values the reconstructed samples, before their DC offset
     * @param offset the DC offset
     * @param inputPrecision the bit depth of the reconstructed component
     * @param inputSigned whether the reconstructed component is signed
     * @return the decoded component's samples
     */
    long[] applyUnrounded(float[] values, double offset, int inputPrecision, boolean inputSigned) {
        var outputMin = signed ? -(1L << (precision - 1)) : 0;
        var outputMax = signed ? (1L << (precision - 1)) - 1 : (1L << precision) - 1;
        var bias = offset + (inputSigned ? Math.scalb(1.0, inputPrecision - 1) : 0);
        var scale = Math.scalb(1.0, -inputPrecision);
        var result = new long[values.length];
        for (var i = 0; i < values.length; i++) {
            result[i] = signMagnitudeLookup((values[i] + bias) * scale, outputMin, outputMax);
        }
        return result;
    }

    /**
     * Maps a sample through the table of type {@link #SIGN_MAGNITUDE_LOOKUP} and converts the result from two's
     * complement to sign-magnitude.
     *
     * @param normalized the sample, normalized to the range {@code [0, 1)} of the reconstructed component
     * @param outputMin the smallest decoded value
     * @param outputMax the largest decoded value
     * @return the decoded value
     */
    private long signMagnitudeLookup(double normalized, long outputMin, long outputMax) {
        var dmin = parameters[0];
        var dmax = parameters[1];
        var points = table.length;
        var t = Double.isNaN(normalized) ? dmin : Math.clamp(normalized, dmin, dmax);
        var delta = (dmax - dmin) / (points - 1);
        var k = Math.min((int) ((t - dmin) / delta), points - 2);
        var z = table[k] + (t - (dmin + k * delta)) / delta * (table[k + 1] - table[k]);
        var value = z * Math.scalb(1.0, precision) - (signed ? Math.scalb(1.0, precision - 1) : 0);
        var rounded = (long) (value < 0 ? -Math.floor(-value + 0.5) : Math.floor(value + 0.5));
        if (signed && rounded < 0) {
            rounded = -rounded - (1L << (precision - 1)) - 1;
        }
        return Math.clamp(rounded, outputMin, outputMax);
    }

    /**
     * Applies the gamma-style transformation to non-normalized samples (equations K-3 to K-5).
     *
     * @param samples the samples, replaced by the decoded component's
     * @param inputPrecision the bit depth of the reconstructed component
     * @param outputMin the smallest decoded value
     * @param outputMax the largest decoded value
     */
    private void gamma(long[] samples, int inputPrecision, long outputMin, long outputMax) {
        var fy = Math.scalb(1.0, inputPrecision) - 1;
        var fz = Math.scalb(1.0, precision) - 1;
        var e = parameters[0];
        var s = parameters[1] * fy / fz;
        var t = parameters[2] * fy;
        var a = parameters[3] * fy / StrictMath.pow(fz, e);
        var b = parameters[4] * fy;
        var inverseE = 1 / e;
        for (var i = 0; i < samples.length; i++) {
            var y = (double) samples[i];
            double z;
            if (y < -t) {
                z = -StrictMath.pow((Math.abs(y) + b) / a, inverseE);
            } else if (y > t) {
                z = StrictMath.pow((y + b) / a, inverseE);
            } else {
                z = y / s;
            }
            samples[i] = round(z, outputMin, outputMax);
        }
    }

    /**
     * Applies the LUT-style transformation (equations K-6 to K-9).
     *
     * @param samples the samples, replaced by the decoded component's
     * @param inputPrecision the bit depth of the reconstructed component
     * @param inputSigned whether the reconstructed component is signed
     * @param outputMin the smallest decoded value
     * @param outputMax the largest decoded value
     */
    private void lookup(long[] samples, int inputPrecision, boolean inputSigned, long outputMin, long outputMax) {
        var dmin = parameters[0];
        var dmax = parameters[1];
        var points = table.length;
        var delta = (dmax - dmin) / (points - 1);
        var range = Math.scalb(1.0, inputPrecision) - 1;
        var bias = inputSigned ? Math.scalb(1.0, inputPrecision - 1) : 0;
        var outputRange = Math.scalb(1.0, precision) - 1;
        var outputBias = signed ? Math.scalb(1.0, precision - 1) : 0;
        for (var i = 0; i < samples.length; i++) {
            var y = Math.clamp((samples[i] + bias) / range, dmin, dmax);
            var k = Math.min((int) ((y - dmin) / delta), points - 2);
            var normalized = table[k] + (y - (dmin + k * delta)) / delta * (table[k + 1] - table[k]);
            samples[i] = round(normalized * outputRange - outputBias, outputMin, outputMax);
        }
    }

    /**
     * Rounds a value to the nearest integer, ties away from zero, and clamps it.
     *
     * @param value the value to round
     * @param min the smallest result
     * @param max the largest result
     * @return the result
     */
    private static long round(double value, long min, long max) {
        if (Double.isNaN(value)) {
            return min;
        }
        var rounded = value < 0 ? -Math.floor(-value + 0.5) : Math.floor(value + 0.5);
        return (long) Math.clamp(rounded, (double) min, (double) max);
    }
}
