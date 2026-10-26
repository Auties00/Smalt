package com.github.auties00.smalt.video.av1;

/**
 * The timing information an {@link Av1Encoder}'s sequence header signals: none, a constant frame
 * rate, or the frame timing with the decoder model of the AV1 specification's Annex E.
 *
 * <p>Containers carry their own timestamps, which players use whatever the stream signals;
 * timing in the stream serves raw elementary streams and checks of a stream against the buffer
 * model of its level.
 */
public enum Av1TimingInfo {
    /**
     * Signals no timing.
     */
    NONE,

    /**
     * Signals that every picture lasts one interval of the options'
     * {@linkplain Av1EncoderOptions#frameRate() frame rate}, and a display model with an initial
     * display delay of eight frames.
     */
    CONSTANT_FRAME_RATE,

    /**
     * Signals the time base, each shown frame's presentation time and the decoder model of the
     * first operating point: decoder and encoder buffer delays of half a second, a decoding tick
     * of one frame interval, and each frame's removal from the decoder buffer, in decoding ticks
     * since the last key frame. Presentation times count display ticks of the
     * {@linkplain Av1EncoderOptions#timeBase() time base} from the removal of the last key frame,
     * delayed by eight frames so that frames coded ahead of their display, as the good-quality
     * pyramids code them, are decoded before they are shown. The frame headers grow by six bytes.
     */
    DECODER_MODEL
}
