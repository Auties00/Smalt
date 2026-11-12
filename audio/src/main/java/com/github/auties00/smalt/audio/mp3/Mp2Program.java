package com.github.auties00.smalt.audio.mp3;

/**
 * Selects which program of an ISO/IEC 13818-3 multichannel Layer II stream an {@link Mp3Decoder} outputs; see
 * {@link Mp3DecoderOptions#program()}.
 *
 * <p>The multichannel extension of a frame carries, besides the main program, either a second stereo program
 * (the {@code 1/0 + 2/0}, {@code 2/0 + 2/0} and {@code 3/0 + 2/0} configurations, where the surround channels are a
 * stereo pair of their own) or up to seven multilingual channels (mono commentaries or dubbed dialogue, at the
 * sampling rate of the main program or at half of it), or both.
 */
public enum Mp2Program {
    /**
     * The main program: the full channel configuration of the extension, the {@code Lo/Ro} base channels of a
     * frame without one.
     */
    MAIN,

    /**
     * The second stereo program, as a {@link com.github.auties00.smalt.audio.ChannelLayout#STEREO} buffer at
     * the sampling rate of the frame.
     */
    SECOND_STEREO,

    /**
     * The multilingual channels, all of them or the one {@link Mp3DecoderOptions#multilingualChannel()} names,
     * at the sampling rate the extension codes them at, the frame's or half of it.
     */
    MULTILINGUAL
}
