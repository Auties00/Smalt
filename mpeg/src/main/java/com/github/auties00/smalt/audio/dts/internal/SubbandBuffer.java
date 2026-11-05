package com.github.auties00.smalt.audio.dts.internal;

import java.util.Arrays;

/**
 * The subband samples of every channel of one DTS frame, up to 64 subbands and 128 samples per subband, each
 * plane preceded by the last {@link #HISTORY} samples of the previous frame for the inverse ADPCM.
 *
 * <p>Instances are not thread-safe.
 */
final class SubbandBuffer {
    /**
     * The number of history samples before the first sample of each plane.
     */
    static final int HISTORY = 4;

    /**
     * The largest number of subband samples per subband in one frame.
     */
    static final int MAX_SAMPLES = 128;

    /**
     * The largest number of channels.
     */
    static final int MAX_CHANNELS = 16;

    /**
     * The planes by channel and subband: {@code HISTORY} history samples, then the samples of the frame.
     */
    final float[][][] planes = new float[MAX_CHANNELS][64][HISTORY + MAX_SAMPLES];

    /**
     * The predictor history kept for the next frame, by channel and subband: the last decoded samples before any
     * extension or channel processing changed them.
     */
    private final float[][][] saved = new float[MAX_CHANNELS][64][HISTORY];

    /**
     * Keeps the last samples of a range of subbands of a channel as the predictor history of the next frame.
     *
     * @param ch the channel
     * @param from the first subband
     * @param to the subband after the last
     * @param samples the number of samples of the frame per subband
     */
    void saveHistory(int ch, int from, int to, int samples) {
        for (var n = from; n < to; n++) {
            System.arraycopy(planes[ch][n], samples, saved[ch][n], 0, HISTORY);
        }
    }

    /**
     * Installs the kept history in front of every plane, for a frame that continues the previous one.
     */
    void restoreHistory() {
        for (var ch = 0; ch < MAX_CHANNELS; ch++) {
            for (var n = 0; n < 64; n++) {
                System.arraycopy(saved[ch][n], 0, planes[ch][n], 0, HISTORY);
            }
        }
    }

    /**
     * Forgets the kept history, as at the start of a stream.
     */
    void clearSaved() {
        for (var channel : saved) {
            for (var plane : channel) {
                Arrays.fill(plane, 0);
            }
        }
    }

    /**
     * Zeroes the history of every plane, for a frame coded without the predictor history of the previous one.
     */
    void clearHistory() {
        for (var channel : planes) {
            for (var plane : channel) {
                Arrays.fill(plane, 0, HISTORY, 0);
            }
        }
    }

    /**
     * Zeroes the samples of a channel's subbands from {@code from} on.
     *
     * @param ch the channel
     * @param from the first subband to zero
     * @param samples the number of samples of the frame per subband
     */
    void clearBands(int ch, int from, int samples) {
        for (var n = from; n < 64; n++) {
            Arrays.fill(planes[ch][n], HISTORY, HISTORY + samples, 0);
        }
    }
}
