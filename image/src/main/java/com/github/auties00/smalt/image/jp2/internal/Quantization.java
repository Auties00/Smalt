package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Holds the quantization of one tile-component, or of one resolution level and precinct: the {@code Sqcd}/{@code SPqcd}
 * fields of a {@code QCD} segment, the {@code Sqcc}/{@code SPqcc} fields of a {@code QCC} segment (ISO/IEC 15444-1
 * A.6.4 and A.6.5), or the {@code Sqpd}/{@code SPqpd} and {@code Sqpc}/{@code SPqpc} fields of the {@code QPD} and
 * {@code QPC} segments of ISO/IEC 15444-2 (A.3.11 and A.3.12), with the variable deadzone and trellis coded styles of
 * ISO/IEC 15444-2 Table A.12.
 *
 * <p>Sub-bands are indexed in codestream order: for a tile-component the lowest resolution's LL band first, then the
 * sub-bands of each higher resolution; for a precinct the sub-bands of its resolution level. With a derived style the
 * exponents of every band derive from the first band's (equation E-5), and so do the deadzones (equation C-2).
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
     * The variable deadzone and scalar derived style of ISO/IEC 15444-2: one deadzone and one step size, the others
     * derived from them.
     */
    public static final int DEADZONE_DERIVED = 3;

    /**
     * The variable deadzone derived and scalar expounded style of ISO/IEC 15444-2: one deadzone for every sub-band and
     * one step size per sub-band.
     */
    public static final int DEADZONE_DERIVED_STEPS_EXPOUNDED = 4;

    /**
     * The variable deadzone and scalar expounded style of ISO/IEC 15444-2: one deadzone and one step size per
     * sub-band.
     */
    public static final int DEADZONE_EXPOUNDED = 5;

    /**
     * The trellis coded derived style of ISO/IEC 15444-2: one step size, the others derived from it.
     */
    public static final int TRELLIS_DERIVED = 9;

    /**
     * The trellis coded expounded style of ISO/IEC 15444-2: one step size per sub-band.
     */
    public static final int TRELLIS_EXPOUNDED = 10;

    /**
     * The largest number of sub-bands of a tile-component: 3 per decomposition level plus one.
     */
    public static final int MAX_BANDS = 3 * (ComponentCoding.MAX_RESOLUTIONS - 1) + 1;

    /**
     * The denominator of a deadzone adjustment, {@code 2^15} (equation C-1).
     */
    private static final float DEADZONE_SCALE = 32768f;

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
     * The signed 16-bit deadzone adjustment {@code num_nz} of each sub-band, or {@code null} when every sub-band has
     * the deadzone of ISO/IEC 15444-1.
     */
    private final int[] deadzones;

    /**
     * Constructs a quantization without deadzone adjustments.
     *
     * @param style     the quantization style
     * @param guardBits the number of guard bits
     * @param exponents the step size exponents, {@link #MAX_BANDS} entries
     * @param mantissas the step size mantissas, {@link #MAX_BANDS} entries
     */
    public Quantization(int style, int guardBits, int[] exponents, int[] mantissas) {
        this(style, guardBits, exponents, mantissas, null);
    }

    /**
     * Constructs a quantization.
     *
     * @param style     the quantization style
     * @param guardBits the number of guard bits
     * @param exponents the step size exponents, {@link #MAX_BANDS} entries
     * @param mantissas the step size mantissas, {@link #MAX_BANDS} entries
     * @param deadzones the signed deadzone adjustments {@code num_nz} of equation C-1 in sub-band order, or
     *                  {@code null} for none
     */
    public Quantization(int style, int guardBits, int[] exponents, int[] mantissas, int[] deadzones) {
        this.style = style;
        this.guardBits = guardBits;
        this.exponents = exponents;
        this.mantissas = mantissas;
        this.deadzones = deadzones;
    }

    /**
     * Parses the quantization fields of a segment.
     *
     * @implNote This implementation follows OpenJPEG's {@code opj_j2k_read_SQcd_SQcc} for the styles of ISO/IEC
     * 15444-1: step sizes beyond {@link #MAX_BANDS} are ignored, and the derived exponents are clamped at zero.
     * @param data   the segment
     * @param offset the index of the {@code Sqcx} byte
     * @param end    the end of the segment
     * @return the quantization
     * @throws MediaException.Malformed if the style is unknown or the segment is too short
     */
    static Quantization parse(byte[] data, int offset, int end) {
        if (end - offset < 1) {
            throw new MediaException.Malformed("truncated JPEG 2000 quantization");
        }
        var sqcx = data[offset] & 0xFF;
        var style = sqcx & 0x1F;
        var guardBits = sqcx >> 5;
        var position = offset + 1;
        var available = end - position;
        var signalled = switch (style) {
            case NONE -> available;
            case SCALAR_DERIVED, TRELLIS_DERIVED, DEADZONE_DERIVED -> 1;
            case SCALAR_EXPOUNDED, TRELLIS_EXPOUNDED -> available / 2;
            case DEADZONE_DERIVED_STEPS_EXPOUNDED -> (available - 2) / 2;
            case DEADZONE_EXPOUNDED -> available / 4;
            default -> throw new MediaException.Malformed("invalid JPEG 2000 quantization style " + style);
        };
        var exponents = new int[Math.max(MAX_BANDS, signalled)];
        var mantissas = new int[exponents.length];
        int[] deadzones = null;
        if (style == NONE) {
            for (var band = 0; band < signalled; band++) {
                exponents[band] = (data[position] & 0xFF) >> 3;
                position++;
            }
            return new Quantization(style, guardBits, exponents, mantissas);
        }
        var valueBytes = style == DEADZONE_DERIVED || style == DEADZONE_EXPOUNDED ? 4 : 2;
        var leading = style == DEADZONE_DERIVED_STEPS_EXPOUNDED ? 2 : 0;
        if (signalled <= 0 || available < leading + valueBytes * signalled) {
            throw new MediaException.Malformed("truncated JPEG 2000 quantization step sizes");
        }
        if (style >= DEADZONE_DERIVED && style <= DEADZONE_EXPOUNDED) {
            deadzones = new int[exponents.length];
        }
        if (leading != 0) {
            var shared = (short) (((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF));
            Arrays.fill(deadzones, shared);
            position += 2;
        }
        for (var band = 0; band < signalled; band++) {
            if (valueBytes == 4) {
                deadzones[band] = (short) (((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF));
                position += 2;
            }
            var value = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
            exponents[band] = value >> 11;
            mantissas[band] = value & 0x7FF;
            position += 2;
        }
        if (derived(style)) {
            for (var band = 1; band < MAX_BANDS; band++) {
                exponents[band] = Math.max(exponents[0] - (band - 1) / 3, 0);
                mantissas[band] = mantissas[0];
                if (deadzones != null) {
                    deadzones[band] = deadzones[0];
                }
            }
        }
        return new Quantization(style, guardBits, exponents, mantissas, deadzones);
    }

    /**
     * Returns whether a style signals one step size from which the others derive.
     *
     * @param style the quantization style
     * @return whether it does
     */
    private static boolean derived(int style) {
        return style == SCALAR_DERIVED || style == DEADZONE_DERIVED || style == TRELLIS_DERIVED;
    }

    /**
     * Returns the quantization style.
     *
     * @return {@link #NONE}, {@link #SCALAR_DERIVED}, {@link #SCALAR_EXPOUNDED}, {@link #DEADZONE_DERIVED},
     * {@link #DEADZONE_DERIVED_STEPS_EXPOUNDED}, {@link #DEADZONE_EXPOUNDED}, {@link #TRELLIS_DERIVED} or
     * {@link #TRELLIS_EXPOUNDED}
     */
    public int style() {
        return style;
    }

    /**
     * Returns whether the step sizes of every sub-band derive from the first one's (equation E-5).
     *
     * @return whether they do
     */
    public boolean derived() {
        return derived(style);
    }

    /**
     * Returns whether the coefficients are trellis coded (ISO/IEC 15444-2 annex D).
     *
     * @return whether they are
     */
    public boolean trellis() {
        return style == TRELLIS_DERIVED || style == TRELLIS_EXPOUNDED;
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
     * Returns twice the step size exponent of a sub-band under derived quantization: equation E-5 with the
     * {@code n_b} of ISO/IEC 15444-2 F.2.4, {@code epsilon_b = epsilon_0 - n_LL + n_b}, where {@code n_LL} is the
     * number of decomposition levels of the lowest resolution's LL band ({@code N_L} for a decomposition whose
     * resolution levels all split both ways).
     *
     * @implNote This implementation counts from the LL band and keeps a half-integer exponent, so that a sub-band
     * split in one direction more than another gets a step size {@code sqrt(2)} times its neighbor's and
     * {@code M_b} counts from the exponent of that step size renormalized to an 11-bit mantissa, as Kakadu 8.4.1
     * writes and decodes such codestreams.
     * @param lowDoubledDepth twice the number of decomposition levels {@code n_LL} of the LL band
     * @param doubledDepth twice the sub-band's number of decomposition levels {@code n_b}
     * @return twice the exponent, at least 0
     */
    int derivedDoubledExponent(int lowDoubledDepth, int doubledDepth) {
        return Math.max(2 * exponents[0] - lowDoubledDepth + doubledDepth, 0);
    }

    /**
     * Returns the step size mantissa of a sub-band.
     *
     * @param band the sub-band index in codestream order
     * @return the 11-bit mantissa
     */
    public int mantissa(int band) {
        return band < mantissas.length ? mantissas[band] : derived() ? mantissas[0] : 0;
    }

    /**
     * Returns the deadzone adjustment {@code nz_b} of a sub-band (ISO/IEC 15444-2 equation C-1).
     *
     * @param band the sub-band index in codestream order
     * @return the adjustment, in {@code [-1, 1)}; 0 for the deadzone of ISO/IEC 15444-1
     */
    public float deadzone(int band) {
        if (deadzones == null) {
            return 0;
        }
        var value = band < deadzones.length ? deadzones[band] : derived() ? deadzones[0] : 0;
        return value / DEADZONE_SCALE;
    }
}
