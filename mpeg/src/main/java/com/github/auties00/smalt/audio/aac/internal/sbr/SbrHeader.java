package com.github.auties00.smalt.audio.aac.internal.sbr;

import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

/**
 * Holds the fields of an {@code sbr_header} of ISO/IEC 14496-3.
 *
 * <p>The optional groups take their default values when the header omits them.
 */
final class SbrHeader {
    /**
     * The amplitude resolution: 0 for 1.5 dB steps, 1 for 3 dB steps.
     */
    int ampResolution;

    /**
     * The index of the start frequency.
     */
    int startFrequency;

    /**
     * The index of the stop frequency.
     */
    int stopFrequency;

    /**
     * The index of the crossover band in the master frequency table.
     */
    int crossoverBand;

    /**
     * The frequency scale: 0 for a linear master table, 1 to 3 for 12, 10 or 8 bands per octave.
     */
    int frequencyScale = 2;

    /**
     * Whether the master table uses the alternative scale.
     */
    int alterScale = 1;

    /**
     * The number of noise floor bands per octave.
     */
    int noiseBands = 2;

    /**
     * The number of limiter bands per octave: 0 for a single band, 1 to 3 for 1.2, 2 or 3.
     */
    int limiterBands = 2;

    /**
     * The index of the limiter gain.
     */
    int limiterGains = 2;

    /**
     * Whether the current envelope energy is estimated per subband rather than per band.
     */
    int interpolateFrequency = 1;

    /**
     * Whether the gains are applied without time smoothing.
     */
    int smoothingMode = 1;

    /**
     * Creates a header with the default values of the optional fields.
     */
    SbrHeader() {
    }

    /**
     * Reads a header.
     *
     * @param reader the bit source, positioned after {@code bs_header_flag}
     */
    void read(AacBitReader reader) {
        ampResolution = reader.read(1);
        startFrequency = reader.read(4);
        stopFrequency = reader.read(4);
        crossoverBand = reader.read(3);
        reader.read(2);
        var extra1 = reader.readFlag();
        var extra2 = reader.readFlag();
        if (extra1) {
            frequencyScale = reader.read(2);
            alterScale = reader.read(1);
            noiseBands = reader.read(2);
        } else {
            frequencyScale = 2;
            alterScale = 1;
            noiseBands = 2;
        }
        if (extra2) {
            limiterBands = reader.read(2);
            limiterGains = reader.read(2);
            interpolateFrequency = reader.read(1);
            smoothingMode = reader.read(1);
        } else {
            limiterBands = 2;
            limiterGains = 2;
            interpolateFrequency = 1;
            smoothingMode = 1;
        }
    }

    /**
     * Returns whether another header defines the same frequency tables.
     *
     * <p>A change of any field the frequency tables derive from resets the decoder, as ISO/IEC
     * 14496-3 requires.
     *
     * @param other the other header
     * @return {@code true} when the tables are the same
     */
    boolean sameTables(SbrHeader other) {
        return startFrequency == other.startFrequency && stopFrequency == other.stopFrequency
                && crossoverBand == other.crossoverBand && frequencyScale == other.frequencyScale
                && alterScale == other.alterScale && noiseBands == other.noiseBands;
    }

    /**
     * Copies every field of another header.
     *
     * @param other the header to copy
     */
    void copy(SbrHeader other) {
        ampResolution = other.ampResolution;
        startFrequency = other.startFrequency;
        stopFrequency = other.stopFrequency;
        crossoverBand = other.crossoverBand;
        frequencyScale = other.frequencyScale;
        alterScale = other.alterScale;
        noiseBands = other.noiseBands;
        limiterBands = other.limiterBands;
        limiterGains = other.limiterGains;
        interpolateFrequency = other.interpolateFrequency;
        smoothingMode = other.smoothingMode;
    }
}
