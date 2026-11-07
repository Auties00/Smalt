package com.github.auties00.smalt.internal.caption;

/**
 * Decodes the packets of a caption stream (closed captions or teletext) into the cues of one channel, service or
 * page, reported to the {@link CaptionTimeline} the implementation was built with.
 *
 * <p>Implementations are stateful and not thread-safe.
 */
public interface CaptionStream {
    /**
     * Decodes one packet.
     *
     * @param data the packet payload, in the form its codec documents
     * @param micros the presentation time of the packet in microseconds
     */
    void decode(byte[] data, long micros);

    /**
     * Ends the caption shown, as at the end of the stream.
     *
     * @param micros the end of the stream in microseconds
     */
    void finish(long micros);

    /**
     * Forgets the screen and the state built from earlier packets, as after a seek.
     */
    void reset();
}
