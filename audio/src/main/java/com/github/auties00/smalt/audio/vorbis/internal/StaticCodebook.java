package com.github.auties00.smalt.audio.vorbis.internal;

/**
 * Describes a Vorbis codebook as the setup header stores it: dimensions, code word lengths and
 * the vector quantization lattice.
 *
 * @implNote This implementation mirrors libvorbis's {@code static_codebook} (BSD-3).
 *
 * @param dim        the number of scalars per vector
 * @param entries    the number of entries
 * @param lengths    the code word length of each entry, {@code 0} for unused entries
 * @param mapType    the lookup type: 0 none, 1 lattice, 2 explicit list
 * @param quantMin   the packed 32-bit float of the smallest value
 * @param quantDelta the packed 32-bit float of the value step
 * @param quantBits  the bits per quantized value
 * @param sequenceP  whether values accumulate along a vector
 * @param quantList  the quantized values: the lattice column for type 1, every value for type 2
 */
public record StaticCodebook(int dim, int entries, byte[] lengths, int mapType, int quantMin, int quantDelta,
                             int quantBits, boolean sequenceP, int[] quantList) {
    /**
     * Returns the number of lattice values per dimension of a type 1 book: the largest
     * {@code v} with {@code v^dim <= entries}.
     *
     * @return the count, at least 1
     */
    public int quantValues() {
        if (entries < 1) {
            return 0;
        }
        var vals = (int) Math.floor(StrictMath.pow((float) entries, 1.0f / dim));
        if (vals < 1) {
            vals = 1;
        }
        while (true) {
            long acc = 1;
            long acc1 = 1;
            var i = 0;
            for (; i < dim; i++) {
                if (entries / vals < acc) {
                    break;
                }
                acc *= vals;
                if (Long.MAX_VALUE / (vals + 1) < acc1) {
                    acc1 = Long.MAX_VALUE;
                } else {
                    acc1 *= vals + 1;
                }
            }
            if (i >= dim && acc <= entries && acc1 > entries) {
                return vals;
            }
            if (i < dim || acc > entries) {
                vals--;
            } else {
                vals++;
            }
        }
    }

    /**
     * Unpacks a packed Vorbis 32-bit float: 21-bit mantissa, 10-bit biased exponent, sign.
     *
     * @param value the packed value
     * @return the float
     */
    public static float unpackFloat(int value) {
        double mantissa = value & 0x1FFFFF;
        var negative = (value & 0x80000000) != 0;
        var exponent = (value & 0x7FE00000) >>> 21;
        if (negative) {
            mantissa = -mantissa;
        }
        exponent = exponent - 20 - 768;
        exponent = Math.clamp(exponent, -63, 63);
        return (float) Math.scalb(mantissa, exponent);
    }
}
