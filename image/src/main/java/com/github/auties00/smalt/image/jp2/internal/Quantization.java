package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Holds the quantization of one tile-component: the {@code Sqcd}/{@code SPqcd} fields of a
 * {@code QCD} segment or the {@code Sqcc}/{@code SPqcc} fields of a {@code QCC} segment
 * (ISO/IEC 15444-1 A.6.4 and A.6.5).
 *
 * <p>Sub-bands are indexed in codestream order: the lowest resolution's LL band first, then the
 * HL, LH and HH bands of each higher resolution. With scalar derived quantization the exponents of
 * every band derive from the LL band's (equation E-5).
 *
 * <p>Instances are immutable once parsed.
 */
public final class Quantization {
    /**
     * The quantization style without quantization (reversible coding).
     */
    public static final int NONE = 0;

    /**
     * The scalar derived quantization style: one step size, the others derived from it.
     */
    public static final int SCALAR_DERIVED = 1;

    /**
     * The scalar expounded quantization style: one step size per sub-band.
     */
    public static final int SCALAR_EXPOUNDED = 2;

    /**
     * The largest number of sub-bands of a tile-component: 3 per decomposition level plus one.
     */
    public static final int MAX_BANDS = 3 * (ComponentCoding.MAX_RESOLUTIONS - 1) + 1;

    /**
     * The quantization style.
     */
    private final int style;

    /**
     * The number of guard bits.
     */
    private final int guardBits;

    /**
     * The step size exponent of each sub-band.
     */
    private final int[] exponents;

    /**
     * The 11-bit step size mantissa of each sub-band.
     */
    private final int[] mantissas;

    /**
     * Constructs a quantization.
     *
     * @param style     the quantization style
     * @param guardBits the number of guard bits
     * @param exponents the step size exponents, {@link #MAX_BANDS} entries
     * @param mantissas the step size mantissas, {@link #MAX_BANDS} entries
     */
    public Quantization(int style, int guardBits, int[] exponents, int[] mantissas) {
        this.style = style;
        this.guardBits = guardBits;
        this.exponents = exponents;
        this.mantissas = mantissas;
    }

    /**
     * Parses the quantization fields of a segment.
     *
     * @implNote This implementation follows OpenJPEG's {@code opj_j2k_read_SQcd_SQcc}: step sizes
     * beyond {@link #MAX_BANDS} are ignored, and the derived exponents are clamped at zero.
     * @param data   the segment
     * @param offset the index of the {@code Sqcx} byte
     * @param end    the end of the segment
     * @return the quantization
     * @throws MediaException.Malformed if the style is unknown or the segment is too short
     * @throws MediaException.Unsupported if the style is a variable deadzone or trellis coded style of ISO/IEC
     * 15444-2
     */
    static Quantization parse(byte[] data, int offset, int end) {
        if (end - offset < 1) {
            throw new MediaException.Malformed("truncated JPEG 2000 quantization");
        }
        var sqcx = data[offset] & 0xFF;
        var style = sqcx & 0x1F;
        var guardBits = sqcx >> 5;
        var position = offset + 1;
        var signalled = style == NONE ? end - position : style == SCALAR_EXPOUNDED ? (end - position) / 2 : 1;
        var exponents = new int[Math.max(MAX_BANDS, signalled)];
        var mantissas = new int[exponents.length];
        switch (style) {
            case NONE -> {
                for (var band = 0; band < signalled; band++) {
                    exponents[band] = (data[position] & 0xFF) >> 3;
                    position++;
                }
            }
            case SCALAR_DERIVED, SCALAR_EXPOUNDED -> {
                if (end - position < 2 * signalled || signalled == 0) {
                    throw new MediaException.Malformed("truncated JPEG 2000 quantization step sizes");
                }
                for (var band = 0; band < signalled; band++) {
                    var value = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
                    exponents[band] = value >> 11;
                    mantissas[band] = value & 0x7FF;
                    position += 2;
                }
                if (style == SCALAR_DERIVED) {
                    for (var band = 1; band < MAX_BANDS; band++) {
                        exponents[band] = Math.max(exponents[0] - (band - 1) / 3, 0);
                        mantissas[band] = mantissas[0];
                    }
                }
            }
            case 3, 4, 5 ->
                // TODO: dequantize with the variable deadzones of ISO/IEC 15444-2 annex C
                    throw new MediaException.Unsupported("JPEG 2000", "variable deadzone quantization");
            case 9, 10 ->
                // TODO: dequantize trellis coded quantization (ISO/IEC 15444-2 annex D)
                    throw new MediaException.Unsupported("JPEG 2000", "trellis coded quantization");
            default -> throw new MediaException.Malformed("invalid JPEG 2000 quantization style " + style);
        }
        return new Quantization(style, guardBits, exponents, mantissas);
    }

    /**
     * Returns the quantization style.
     *
     * @return {@link #NONE}, {@link #SCALAR_DERIVED} or {@link #SCALAR_EXPOUNDED}
     */
    public int style() {
        return style;
    }

    /**
     * Returns the number of guard bits.
     *
     * @return the guard bits, from 0 to 7
     */
    public int guardBits() {
        return guardBits;
    }

    /**
     * Returns the step size exponent of a sub-band.
     *
     * @param band the sub-band index in codestream order
     * @return the exponent
     */
    public int exponent(int band) {
        return band < exponents.length ? exponents[band] : 0;
    }

    /**
     * Returns the step size exponent of a sub-band of an arbitrary decomposition under scalar derived quantization
     * (ISO/IEC 15444-1 equation E-5 with the {@code n_b} of ISO/IEC 15444-2 F.2.4).
     *
     * @param levels the number of decomposition levels {@code N_L}
     * @param doubledDepth twice the sub-band's number of decomposition levels {@code n_b}
     * @return the exponent, at least 0
     * @throws MediaException.Unsupported if {@code n_b} is not an integer
     */
    int derivedExponent(int levels, int doubledDepth) {
        if ((doubledDepth & 1) != 0) {
            // TODO: derive the step sizes of sub-bands split in one direction only, once the rounding of a half-integer
            //  n_b in equation E-5 is settled
            throw new MediaException.Unsupported("JPEG 2000", "derived quantization of sub-bands split in one direction");
        }
        return Math.max(exponents[0] - levels + doubledDepth / 2, 0);
    }

    /**
     * Returns the step size mantissa of a sub-band.
     *
     * @param band the sub-band index in codestream order
     * @return the 11-bit mantissa
     */
    public int mantissa(int band) {
        return band < mantissas.length ? mantissas[band] : style == SCALAR_DERIVED ? mantissas[0] : 0;
    }
}
