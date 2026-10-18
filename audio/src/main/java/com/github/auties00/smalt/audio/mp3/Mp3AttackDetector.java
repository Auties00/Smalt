package com.github.auties00.smalt.audio.mp3;

/**
 * Detects attacks in one channel, which call for short blocks to keep pre-echo inside a short
 * window.
 *
 * <p>The detector works on the subband samples of a granule: it sums the energy of the subbands
 * above about 1.2 kHz over sub-blocks of three time slots (96 input samples) and reports an attack
 * when a sub-block's energy exceeds {@link #RATIO} times the mean energy of the
 * {@link #HISTORY} sub-blocks before it, and is loud enough to matter.
 *
 * @implNote This implementation follows the energy-ratio detectors of the literature on window
 * switching (for example 3GPP TS 26.403 5.4.1 for AAC, which compares high-passed sub-block
 * energies against a decaying average); using the polyphase subbands as the high-pass filter
 * aligns the sub-blocks with the MDCT windows.
 */
final class Mp3AttackDetector {
    /**
     * The energy ratio over the recent mean that counts as an attack.
     */
    static final float RATIO = 10;

    /**
     * The number of earlier sub-blocks whose mean energy an attack is compared with.
     */
    private static final int HISTORY = 8;

    /**
     * The energy below which a sub-block never counts as an attack, about -70 dB relative to a
     * full-scale signal in every subband.
     */
    private static final float FLOOR = 1e-7f;

    /**
     * The first subband whose energy is measured.
     */
    private final int firstSubband;

    /**
     * The energies of the most recent sub-blocks, oldest first.
     */
    private final float[] history = new float[HISTORY];

    /**
     * Creates a detector.
     *
     * @param sampleRate the sampling rate in hertz
     */
    Mp3AttackDetector(int sampleRate) {
        this.firstSubband = Math.clamp(Math.round(1200f / (sampleRate / 64f)), 1, 8);
    }

    /**
     * Examines the subband samples of a granule and returns where it holds attacks.
     *
     * @param subbands the subband samples, subband {@code k}, slot {@code t} at {@code 18 k + t}
     * @return a mask with bit {@code j} set when sub-block {@code j} (slots {@code 3 j} to
     *         {@code 3 j + 2}) holds an attack, {@code 0} for none
     */
    int detect(float[] subbands) {
        var attacks = 0;
        for (var block = 0; block < 6; block++) {
            var sum = 0.0f;
            for (var k = firstSubband; k < 32; k++) {
                var base = 18 * k + 3 * block;
                var a = subbands[base];
                var b = subbands[base + 1];
                var c = subbands[base + 2];
                sum += a * a + b * b + c * c;
            }
            var mean = 0.0f;
            for (var h = 0; h < HISTORY; h++) {
                mean += history[h];
            }
            mean /= HISTORY;
            if (sum > FLOOR && sum > RATIO * mean) {
                attacks |= 1 << block;
            }
            System.arraycopy(history, 1, history, 0, HISTORY - 1);
            history[HISTORY - 1] = sum;
        }
        return attacks;
    }
}
