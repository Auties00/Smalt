package com.github.auties00.smalt.internal.header;

/**
 * Maps the AAC sampling frequency index of ISO/IEC 14496-3 Table 1.18 to its rate and back.
 *
 * <p>Indices 0 to 12 name a rate; 13 and 14 are reserved, and 15 escapes to an explicit 24-bit rate
 * in an {@code AudioSpecificConfig}. This class is thread-safe.
 */
public final class AacRates {
    /**
     * The rate of each index, in hertz; 0 for the reserved and escape indices.
     */
    private static final int[] RATES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350, 0, 0, 0
    };

    /**
     * The index that escapes to an explicit rate.
     */
    public static final int ESCAPE = 15;

    /**
     * Prevents instantiation.
     */
    private AacRates() {
        throw new AssertionError();
    }

    /**
     * Returns the rate an index names.
     *
     * @param index the sampling frequency index
     * @return the rate in hertz, or 0 when {@code index} is reserved, the escape or outside 0 to 15
     */
    public static int rate(int index) {
        return index < 0 || index >= RATES.length ? 0 : RATES[index];
    }

    /**
     * Returns the index that names a rate.
     *
     * @param rate the rate in hertz
     * @return the index, from 0 to 12, or {@code -1} when no index names {@code rate} exactly
     */
    public static int index(int rate) {
        if (rate <= 0) {
            return -1;
        }
        for (var i = 0; i < 13; i++) {
            if (RATES[i] == rate) {
                return i;
            }
        }
        return -1;
    }
}
