/* Ports webrtc-audio-processing 2.1 (api/audio/echo_canceller3_config.h, modules/audio_processing/aec3/
   echo_canceller3.cc). Copyright (c) 2011, The WebRTC project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

/**
 * Holds the settings of the echo canceller that differ between its mono and its multichannel
 * configuration; every other setting is a constant of {@link Aec3}.
 *
 * @param coarse the rate and noise gate of the coarse filter update after the initial state
 * @param coarseInitial the rate and noise gate of the coarse filter update in the initial state
 * @param coarseLengthBlocks the length of the coarse filter after the initial state, in blocks
 * @param coarseInitialLengthBlocks the length of the coarse filter in the initial state, in blocks
 * @param normalMaxIncFactor the largest gain increase per block of the suppressor's normal tuning
 * @param normalMaxDecFactorLf the largest low-frequency gain decrease per block of the
 * suppressor's normal tuning
 */
record Aec3Config(float[] coarse, float[] coarseInitial, int coarseLengthBlocks, int coarseInitialLengthBlocks,
                  float normalMaxIncFactor, float normalMaxDecFactorLf) {
    /**
     * The default configuration ({@code EchoCanceller3Config}).
     */
    static final Aec3Config DEFAULT = new Aec3Config(new float[]{0.7f, 20075344.f}, new float[]{0.9f, 20075344.f},
            13, 12, 2.0f, 0.25f);

    /**
     * The configuration used once the render signal carries multichannel content
     * ({@code EchoCanceller3::CreateDefaultMultichannelConfig}).
     */
    static final Aec3Config MULTICHANNEL = new Aec3Config(new float[]{0.95f, 20075344.f},
            new float[]{0.95f, 20075344.f}, 11, 11, 1.5f, 0.35f);
}
