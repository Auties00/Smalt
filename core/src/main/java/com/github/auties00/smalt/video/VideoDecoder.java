package com.github.auties00.smalt.video;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.Packet;

import java.util.function.Consumer;

/**
 * Decodes the packets of one video track into pictures, in presentation order.
 *
 * <p>Packets are fed in decoding order through {@link #decode(Packet, Consumer)}. Codecs with
 * frame reordering (B-frames) hold pictures back until they are due, so a packet may yield no
 * frame or several; {@link #flush(Consumer)} drains the held pictures once the input ends. Frames
 * reach the consumer in presentation order with the timestamps of the packets they were coded in.
 * The picture of each {@link VideoFrame} is recycled by the decoder and valid only until the
 * consumer returns.
 *
 * @implSpec
 * Implementations must raise {@link MediaException.Malformed} for a corrupt packet rather than
 * any unchecked runtime exception, must check every coded picture size against the limits they
 * were created with before allocating, and must not retain the packet's array after
 * {@link #decode(Packet, Consumer)} returns unless they copied it.
 */
public interface VideoDecoder extends AutoCloseable {
    /**
     * Decodes one packet and hands every frame that became due to {@code output}.
     *
     * @param packet the next packet in decoding order
     * @param output receives zero or more frames, each valid only during the call
     * @throws MediaException.Malformed     if the packet is corrupt
     * @throws MediaException.Unsupported   if the packet uses a coding tool this decoder lacks
     * @throws MediaException.LimitExceeded if the coded picture exceeds the decoder's limits
     */
    void decode(Packet packet, Consumer<VideoFrame> output);

    /**
     * Hands every picture the decoder still holds to {@code output} after the last packet.
     *
     * @param output receives zero or more frames, each valid only during the call
     */
    void flush(Consumer<VideoFrame> output);

    /**
     * Releases the decoder's resources, including any worker threads.
     *
     * <p>The default implementation holds none and does nothing.
     */
    @Override
    default void close() {
    }
}
