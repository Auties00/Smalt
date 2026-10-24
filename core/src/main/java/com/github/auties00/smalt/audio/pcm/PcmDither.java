package com.github.auties00.smalt.audio.pcm;

/**
 * Selects the dither {@link PcmEncoder} adds before it rounds samples to an integer format of 8, 16 or
 * 24 bits.
 *
 * <p>Rounding without dither leaves a quantization error that follows the signal, heard as distortion
 * on quiet passages and fades. Triangular (TPDF) dither of two least significant bits peak to peak
 * makes the mean and the power of the error independent of the signal (Lipshitz, Wannamaker and
 * Vanderkooy, "Quantization and Dither: A Theoretical Survey", 1992), at the cost of a constant noise
 * floor three times the power of plain rounding error ({@code 1/4} instead of {@code 1/12} squared
 * LSB, about 4.8 dB more). Noise shaping then moves that floor towards the frequencies the ear is least
 * sensitive to, raising its total power.
 *
 * <p>The dither sequence is pseudo-random with a fixed seed per channel, so an encoder's output is
 * deterministic and does not depend on how the input is split into blocks.
 */
public enum PcmDither {
    /**
     * No dither: samples are rounded to nearest, ties up.
     */
    NONE,

    /**
     * Triangular dither of plus or minus one LSB, the sum of two independent uniform values, with a
     * white spectrum.
     */
    TRIANGULAR,

    /**
     * Triangular dither of plus or minus one LSB made as the difference of consecutive uniform values,
     * whose spectrum rises towards the Nyquist frequency and is lowest where hearing is most sensitive.
     */
    TRIANGULAR_HIGH_PASS,

    /**
     * Triangular dither with error-feedback noise shaping by the five-tap E-weighted filter of Lipshitz,
     * Vanderkooy and Wannamaker ("Minimally Audible Noise Shaping", JAES 39(11), 1991), designed for
     * 44.1 kHz: the requantization noise falls by about 15 dB up to 5 kHz (27 dB near 4 kHz, where
     * hearing is most sensitive) and rises above 13 kHz, by up to about 19 dB at the Nyquist frequency.
     * At other sampling rates the shaped spectrum scales with the rate; below 32 kHz it moves noise into
     * the most audible band and is not recommended.
     */
    NOISE_SHAPED
}
