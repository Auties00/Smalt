/* Ports jxl-oxide 0.12.6 (crates/jxl-image: BitDepth). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.header;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;

/**
 * Describes how the integer samples of a channel map to nominal values (ISO/IEC 18181-1 table
 * A.15, {@code BitDepth}).
 *
 * <p>An integer channel of {@code bitsPerSample} bits maps {@code 0..2^bits-1} linearly to
 * {@code 0.0..1.0}; a floating-point channel stores the bit pattern of a binary floating-point
 * value with one sign bit, {@code expBits} exponent bits and the remaining mantissa bits.
 *
 * @param floatSample whether the samples are floating-point bit patterns
 * @param bitsPerSample the number of bits of a sample, from 1 to 31 for integers and from 4 to 32
 * for floating-point values
 * @param expBits the number of exponent bits of a floating-point sample, from 2 to 8, or 0
 * for integers
 */
public record BitDepth(boolean floatSample, int bitsPerSample, int expBits) {
    /**
     * The default depth: 8-bit integers.
     */
    public static final BitDepth DEFAULT = new BitDepth(false, 8, 0);

    /**
     * Reads a bit depth.
     *
     * @param br the bitstream
     * @return the depth
     * @throws MediaException.Malformed if the depth is outside the ranges the specification allows
     */
    public static BitDepth read(JxlBitReader br) {
        if (br.readBool()) {
            var bits = br.readU32(32, 0, 16, 0, 24, 0, 1, 6);
            var exp = br.readBits(4) + 1;
            if (exp < 2 || exp > 8) {
                throw new MediaException.Malformed("invalid float sample exponent bits " + exp);
            }
            var mantissa = bits - exp - 1;
            if (mantissa < 2 || mantissa > 23) {
                throw new MediaException.Malformed("invalid float sample mantissa bits " + mantissa);
            }
            return new BitDepth(true, bits, exp);
        }
        var bits = br.readU32(8, 0, 10, 0, 12, 0, 1, 6);
        if (bits > 31) {
            throw new MediaException.Malformed("invalid bits per sample " + bits);
        }
        return new BitDepth(false, bits, 0);
    }

    /**
     * Returns the integer sample that means full intensity (opaque alpha, full ink).
     *
     * @return {@code 2^bits - 1} for integers, the bit pattern of {@code 1.0} for floating-point
     * samples
     */
    public int maxValue() {
        if (floatSample) {
            var mantissaBits = bitsPerSample - expBits - 1;
            return ((1 << (expBits - 1)) - 1) << mantissaBits;
        }
        return (int) ((1L << bitsPerSample) - 1);
    }

    /**
     * Converts a decoded integer sample to its nominal floating-point value.
     *
     * @param sample the decoded integer sample or float bit pattern
     * @return the nominal value; integer samples outside {@code 0..2^bits-1} are not clamped
     */
    public float toFloat(int sample) {
        if (!floatSample) {
            return sample / (float) ((1L << bitsPerSample) - 1);
        }
        var mantissaBits = bitsPerSample - expBits - 1;
        var mantissaMask = (1 << mantissaBits) - 1;
        var expMask = ((1 << (bitsPerSample - 1)) - 1) ^ mantissaMask;
        var signed = (sample & (1 << (bitsPerSample - 1))) != 0;
        var mantissa = sample & mantissaMask;
        var bias = (1 << (expBits - 1)) - 1;
        var exponent = ((sample & expMask) >>> mantissaBits);
        if (bitsPerSample == 32) {
            return Float.intBitsToFloat(sample);
        }
        float value;
        if (exponent == 0) {
            value = (float) Math.scalb((double) mantissa, 1 - bias - mantissaBits);
        } else if (exponent == (1 << expBits) - 1) {
            value = mantissa == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        } else {
            value = (float) Math.scalb((double) (mantissa | (1 << mantissaBits)), exponent - bias - mantissaBits);
        }
        return signed ? -value : value;
    }

    /**
     * Returns the scale that converts integer samples to nominal values.
     *
     * @return {@code 1 / (2^bits - 1)} for integers; meaningless for floating-point samples
     */
    public float scale() {
        return 1.0f / (float) ((1L << bitsPerSample) - 1);
    }
}
