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
     * {@linkplain Av1EncoderOptions#frameRate() frame rate}, and a display model with the initial
     * display delay and the level of {@link #DECODER_MODEL}.
     */
    CONSTANT_FRAME_RATE,

    /**
     * Signals the time base, each shown frame's presentation time and the decoder model of the
     * first operating point, in decoding schedule mode, with a stream that conforms to the decoder
     * model of its level as a whole and from each key frame on.
     *
     * <p>The level becomes the lowest whose limits admit the picture size shown at the options'
     * {@linkplain Av1EncoderOptions#frameRate() frame rate} and decoded at that rate, or, in good
     * quality, at five decodes per four frames, which leaves time for the hidden alternate
     * references and coded overlays of the shortest frame groups, and whose bitrate is at least
     * one and a half times the {@linkplain Av1EncoderOptions#bitrate() bitrate} of rate-controlled
     * coding. The decoder and encoder buffer delays add up to the level's smoothing buffer, one
     * second at its bitrate, so the buffer never overflows; the decoder buffer delay is the time the
     * largest frame the level's compression ratio admits takes to arrive, at most 70000/90000
     * seconds. Presentation times count display ticks of the
     * {@linkplain Av1EncoderOptions#timeBase() time base} from the last key frame's presentation,
     * a key frame's from the previous key frame's. The initial display delay, the frames the
     * decoder holds before it shows the first, is eight frames in good quality, for the frames its
     * pyramids code ahead of their display, and in real time the frames its references fill after
     * a key frame (seven, or the number of temporal layers), so that the decoder model's buffer pool
     * reaches it and the presentation starts.
     * Each frame's removal time counts decoding ticks of about a sixteenth of a frame interval from the
     * last key frame's removal (a key frame's from the previous key frame's): the frame's timestamp
     * after the first frame's removal, delayed, from the frame's coded size, until the frame group
     * has arrived in the smoothing buffer, the frame before has been decoded at the level's
     * decoding speed and header rate, a frame buffer is free, and, for a key frame, a decoder
     * buffer delay has passed since the frame group before arrived. The frame headers grow by
     * eight bytes.
     *
     * @apiNote Constant quality coding does not bound the bitrate: a stream that spends more than
     * the level's bitrate for longer than the smoothing buffer absorbs has its removals delayed
     * past the presentation of its frames, and does not conform. Rate-controlled coding targets a bitrate
     * well below the level's bitrate, which the level is chosen for.
     */
    DECODER_MODEL
}
