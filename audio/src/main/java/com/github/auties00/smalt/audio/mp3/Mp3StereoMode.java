package com.github.auties00.smalt.audio.mp3;

/**
 * Selects how an {@link Mp3Encoder} or an {@link Mp2Encoder} codes the two channels of a stereo
 * stream; mono streams ignore it.
 */
public enum Mp3StereoMode {
    /**
     * Left and right coded separately, sharing the frame's bits.
     */
    STEREO,

    /**
     * Joint stereo. An {@link Mp3Encoder} codes left and right, or their sum and difference
     * (mid/side) when that needs fewer bits for the same masked noise, in each frame, and uses no
     * intensity stereo; an {@link Mp2Encoder} codes the upper subbands of each frame as intensity
     * stereo, as {@link Mp2EncoderOptions#stereoMode()} describes.
     */
    JOINT_STEREO,

    /**
     * Two independent channels (for example two languages), signaled as dual channel.
     */
    DUAL_CHANNEL
}
