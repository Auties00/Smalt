/* Ports sac_parallelReverb.c of the mp4spatialdec module of the ISO/IEC 23003-3 reference software.
   Copyright Agere Systems, Coding Technologies, Fraunhofer IIS, Philips and ISO/IEC 2008; ISO/IEC reference software
   licence (use in products conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.mps;

/**
 * Adds the reverb of a data set to a binaural output: the sum of the downmix channels, delayed to line up with the
 * output of the QMF path, convolved in the time domain with the reverb response of each ear. Instances keep the state
 * of one stream and are not thread-safe.
 */
final class MpsReverb {
    private final float[][] response;
    private final int offset;
    private final int taps;
    private final int frameLength;
    private final int maxDelay;
    private final float[] history;

    /**
     * Constructs a reverb.
     *
     * @param hrtf the data set, which has a reverb
     * @param frameLength the samples of a frame
     * @param maxDelay the longest delay of the downmix, in samples
     */
    MpsReverb(MpsHrtf hrtf, int frameLength, int maxDelay) {
        this.response = new float[][]{hrtf.reverbLeft, hrtf.reverbRight};
        var length = hrtf.reverbLeft.length;
        var first = length;
        for (var i = 0; i < length; i++) {
            if (hrtf.reverbLeft[i] != 0 || hrtf.reverbRight[i] != 0) {
                first = i;
                break;
            }
        }
        this.offset = first;
        this.taps = length - first;
        this.frameLength = frameLength;
        this.maxDelay = maxDelay;
        this.history = new float[length + maxDelay + frameLength - 1];
    }

    /**
     * Adds the reverb of one frame.
     *
     * @param input the downmix channels
     * @param inputOffset the index of the first downmix sample
     * @param gain the gain of the downmix
     * @param delay the delay of the downmix against the output, in samples, at most the longest delay
     * @param output the two output channels, added to from index 0
     */
    void process(float[][] input, int inputOffset, float gain, int delay, double[][] output) {
        var keep = history.length - frameLength;
        System.arraycopy(history, frameLength, history, 0, keep);
        for (var i = 0; i < frameLength; i++) {
            var sum = 0.0f;
            for (var channel : input) {
                sum += channel[inputOffset + i] * gain;
            }
            history[keep + i] = sum;
        }
        var first = keep - delay - offset;
        for (var ear = 0; ear < 2; ear++) {
            var ir = response[ear];
            var out = output[ear];
            for (var i = 0; i < frameLength; i++) {
                var sum = 0.0f;
                var at = first + i;
                for (var l = 0; l < taps; l++) {
                    sum += ir[l + offset] * history[at - l];
                }
                out[i] += sum;
            }
        }
    }
}
