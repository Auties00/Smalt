/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/alignment_mixer.cc). Copyright (c) 2011, The
   WebRTC project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;

/**
 * Picks the channel of a multichannel block the delay estimator aligns on: the channel with the
 * most energy, switching only to a channel with twice the energy of the selected one
 * ({@code AlignmentMixer} with adaptive selection, the default of both the render and the
 * capture side). With one channel it returns that channel.
 */
final class AlignmentMixer {
    /**
     * The number of strong blocks after which the first two channels are preferred.
     */
    private static final int BLOCKS_TO_CHOOSE_LEFT_OR_RIGHT = (int) (0.5f * Aec3.NUM_BLOCKS_PER_SECOND);

    /**
     * The number of blocks over which the energies are summed before they are smoothed.
     */
    private static final int NUM_BLOCKS_BEFORE_ENERGY_SMOOTHING = 60 * Aec3.NUM_BLOCKS_PER_SECOND;

    /**
     * The number of channels.
     */
    private final int numChannels;

    /**
     * The block energy above which a block of the first two channels counts as strong.
     */
    private final float excitationEnergyThreshold;

    /**
     * Whether the first two channels are preferred once one has strong content.
     */
    private final boolean preferFirstTwoChannels;

    /**
     * The number of strong blocks of the first two channels.
     */
    private final int[] strongBlockCounters = new int[2];

    /**
     * The summed, then smoothed, energy of every channel.
     */
    private final float[] cumulativeEnergies;

    /**
     * The number of selections made.
     */
    private int blockCounter;

    /**
     * The selected channel.
     */
    private int selectedChannel;

    /**
     * Creates a mixer.
     *
     * @param numChannels the number of channels
     * @param preferFirstTwoChannels whether the first two channels are preferred, as on the render
     * side
     */
    AlignmentMixer(int numChannels, boolean preferFirstTwoChannels) {
        this.numChannels = numChannels;
        this.excitationEnergyThreshold = BLOCK_SIZE * 10000.f;
        this.preferFirstTwoChannels = preferFirstTwoChannels;
        this.cumulativeEnergies = new float[numChannels];
    }

    /**
     * Copies the selected channel of a block.
     *
     * @param x the blocks
     * @param offset the index of the first sample of channel 0
     * @param stride the distance between the first samples of two channels
     * @param y receives the 64 samples of the selected channel
     */
    void produceOutput(float[] x, int offset, int stride, float[] y) {
        var ch = numChannels == 1 ? 0 : selectChannel(x, offset, stride);
        System.arraycopy(x, offset + ch * stride, y, 0, BLOCK_SIZE);
    }

    /**
     * Updates the channel energies and selects a channel ({@code SelectChannel}).
     *
     * @param x the blocks
     * @param offset the index of the first sample of channel 0
     * @param stride the distance between the first samples of two channels
     * @return the selected channel
     */
    private int selectChannel(float[] x, int offset, int stride) {
        var goodSignalInLeftOrRight = preferFirstTwoChannels
                && (strongBlockCounters[0] > BLOCKS_TO_CHOOSE_LEFT_OR_RIGHT
                || strongBlockCounters[1] > BLOCKS_TO_CHOOSE_LEFT_OR_RIGHT);
        var numChToAnalyze = goodSignalInLeftOrRight ? 2 : numChannels;
        ++blockCounter;
        for (var ch = 0; ch < numChToAnalyze; ++ch) {
            var x2Sum = 0.f;
            var o = offset + ch * stride;
            for (var i = 0; i < BLOCK_SIZE; ++i) {
                x2Sum += x[o + i] * x[o + i];
            }
            if (ch < 2 && x2Sum > excitationEnergyThreshold) {
                ++strongBlockCounters[ch];
            }
            if (blockCounter <= NUM_BLOCKS_BEFORE_ENERGY_SMOOTHING) {
                cumulativeEnergies[ch] += x2Sum;
            } else {
                var smoothing = 1.f / (10 * Aec3.NUM_BLOCKS_PER_SECOND);
                cumulativeEnergies[ch] += smoothing * (x2Sum - cumulativeEnergies[ch]);
            }
        }
        if (blockCounter == NUM_BLOCKS_BEFORE_ENERGY_SMOOTHING) {
            var oneByNumBlocksBeforeEnergySmoothing = 1.f / NUM_BLOCKS_BEFORE_ENERGY_SMOOTHING;
            for (var ch = 0; ch < numChToAnalyze; ++ch) {
                cumulativeEnergies[ch] *= oneByNumBlocksBeforeEnergySmoothing;
            }
        }
        var strongestCh = 0;
        for (var ch = 0; ch < numChToAnalyze; ++ch) {
            if (cumulativeEnergies[ch] > cumulativeEnergies[strongestCh]) {
                strongestCh = ch;
            }
        }
        if ((goodSignalInLeftOrRight && selectedChannel > 1)
                || cumulativeEnergies[strongestCh] > 2.f * cumulativeEnergies[selectedChannel]) {
            selectedChannel = strongestCh;
        }
        return selectedChannel;
    }
}
