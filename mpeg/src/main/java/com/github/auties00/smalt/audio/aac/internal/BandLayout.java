/* Ports vo-aacenc 0.1.3 (aacenc/src/psy_configuration.c). Copyright 2003-2010 VisualOn, Inc.; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal;

/**
 * Describes the scalefactor bands of one window size for the encoder, with the psychoacoustic
 * constants of each band: the threshold in quiet, the masking slopes to the neighboring bands,
 * and the minimum signal-to-noise ratio that prevents spectral holes.
 *
 * <p>Energies are expressed in the encoder's MDCT domain, where a full-scale sine of one window
 * carries an energy of about {@code (lines * 32768)^2}.
 *
 * @implNote This implementation follows the psychoacoustic model of 3GPP TS 26.403: masking slopes of 15 dB per
 * Bark towards higher and 30 dB per Bark towards lower frequencies, a threshold in quiet taken
 * from its Bark table and set 20 dB lower, with 96 dB SPL at full scale, and minimum SNRs
 * that grant every active Bark 2.4 percent of the frame's perceptual entropy.
 */
public final class BandLayout {
    /**
     * The threshold in quiet of each integer Bark value from 0 to 24, in dB SPL (3GPP TS 26.403).
     */
    private static final double[] QUIET_BY_BARK = {
            15, 10, 7, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 5, 10, 20, 30
    };

    /**
     * The number of spectral lines of one window.
     */
    private final int windowLength;

    /**
     * The band offsets, including the end offset.
     */
    private final int[] offsets;

    /**
     * The number of bands.
     */
    private final int count;

    /**
     * The number of bands that start below the low-pass line.
     */
    private final int active;

    /**
     * The first spectral line removed by the low-pass filter.
     */
    private final int lowpassLine;

    /**
     * The threshold in quiet of each band.
     */
    private final float[] quiet;

    /**
     * The masking factor from each band to the next higher one, indexed by the higher band.
     */
    private final float[] spreadUp;

    /**
     * The masking factor from each band to the next lower one, indexed by the lower band.
     */
    private final float[] spreadDown;

    /**
     * The minimum threshold-to-energy ratio of each band below which no hole is punched.
     */
    private final float[] minSnr;

    /**
     * The center of each band on the Bark scale.
     */
    private final float[] bark;

    /**
     * Computes the layout of one window size.
     *
     * @param sampleRate the sampling rate in hertz
     * @param shortWindow whether the layout is for short windows
     * @param bandwidth the audio bandwidth in hertz
     * @param channelBitrate the bitrate per channel in bits per second
     */
    public BandLayout(int sampleRate, boolean shortWindow, int bandwidth, int channelBitrate) {
        var tableIndex = SamplingTables.tableIndexFor(sampleRate);
        this.offsets = shortWindow ? SamplingTables.shortOffsets(tableIndex, 1024) : SamplingTables.longOffsets(tableIndex, 1024);
        this.windowLength = shortWindow ? 128 : 1024;
        this.count = offsets.length - 1;
        this.lowpassLine = Math.min(windowLength, (int) ((long) bandwidth * 2 * windowLength / sampleRate));
        var activeBands = 0;
        while (activeBands < count && offsets[activeBands] < lowpassLine) {
            activeBands++;
        }
        this.active = activeBands;
        this.quiet = new float[count];
        this.spreadUp = new float[count];
        this.spreadDown = new float[count];
        this.minSnr = new float[count];
        this.bark = new float[count];
        var lineWidth = sampleRate / (2.0 * windowLength);
        var fullScale = (double) windowLength * windowLength * 32768.0 * 32768.0;
        for (var band = 0; band < count; band++) {
            var low = offsets[band] * lineWidth;
            var high = offsets[band + 1] * lineWidth;
            bark[band] = (float) bark((low + high) / 2);
            var threshold = Math.min(hearingThreshold(bark(low)), hearingThreshold(bark(high)));
            var perLine = fullScale * Math.pow(10, (threshold - 96 - 20) / 10);
            quiet[band] = (float) (perLine * (offsets[band + 1] - offsets[band]));
        }
        for (var band = 1; band < count; band++) {
            var distance = bark[band] - bark[band - 1];
            spreadUp[band] = (float) Math.pow(10, -15 * distance / 10);
            spreadDown[band - 1] = (float) Math.pow(10, -30 * distance / 10);
        }
        var bitsPerWindow = (double) channelBitrate * windowLength / sampleRate;
        var pePerWindow = 1.18 * bitsPerWindow;
        var lastBark = active > 0 ? bark(offsets[active] * lineWidth) : 1;
        var previousEdge = 0.0;
        for (var band = 0; band < count; band++) {
            var edge = bark(offsets[band + 1] * lineWidth);
            var width = edge - previousEdge;
            previousEdge = edge;
            var lines = offsets[band + 1] - offsets[band];
            var perLine = 0.024 * pePerWindow * 24 * width / (lastBark * lines);
            perLine = Math.clamp(perLine, 1.4, 8.4);
            var snr = 1 / (Math.pow(2, perLine) - 1.5);
            minSnr[band] = (float) Math.clamp(snr, Math.pow(10, -2.5), Math.pow(10, -0.1));
        }
    }

    /**
     * Returns the Bark value of a frequency.
     *
     * @param frequency the frequency in hertz
     * @return the Bark value
     */
    private static double bark(double frequency) {
        return 13 * Math.atan(0.00076 * frequency) + 3.5 * Math.atan(Math.pow(frequency / 7500, 2));
    }

    /**
     * Returns the threshold in quiet at a Bark value.
     *
     * @param bark the Bark value
     * @return the threshold in dB SPL
     */
    private static double hearingThreshold(double bark) {
        return QUIET_BY_BARK[Math.clamp((int) bark, 0, QUIET_BY_BARK.length - 1)];
    }

    /**
     * Returns the number of spectral lines of one window.
     *
     * @return 1024 or 128
     */
    public int windowLength() {
        return windowLength;
    }

    /**
     * Returns the band offsets, including the end offset.
     *
     * @return the offsets; shared and not to be modified
     */
    public int[] offsets() {
        return offsets;
    }

    /**
     * Returns the number of bands.
     *
     * @return the band count
     */
    public int count() {
        return count;
    }

    /**
     * Returns the number of bands that start below the low-pass line.
     *
     * @return the active band count
     */
    public int active() {
        return active;
    }

    /**
     * Returns the first spectral line the low-pass filter removes.
     *
     * @return the line index
     */
    public int lowpassLine() {
        return lowpassLine;
    }

    /**
     * Returns the threshold in quiet of a band.
     *
     * @param band the scalefactor band index
     * @return the threshold energy
     */
    public float quiet(int band) {
        return quiet[band];
    }

    /**
     * Returns the masking factor from a band to the next higher one.
     *
     * @param band the higher band, at least 1
     * @return the factor
     */
    public float spreadUp(int band) {
        return spreadUp[band];
    }

    /**
     * Returns the masking factor from a band to the next lower one.
     *
     * @param band the lower band
     * @return the factor
     */
    public float spreadDown(int band) {
        return spreadDown[band];
    }

    /**
     * Returns the minimum threshold-to-energy ratio of a band.
     *
     * @param band the scalefactor band index
     * @return the ratio, from -25 dB to -1 dB
     */
    public float minSnr(int band) {
        return minSnr[band];
    }
}
