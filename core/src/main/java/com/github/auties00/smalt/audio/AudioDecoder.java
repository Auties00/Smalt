package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.Packet;

import java.util.function.Consumer;

/**
 * Decodes the packets of one audio track into planar float samples.
 *
 * <p>Packets are fed in decoding order through {@link #decode(Packet, Consumer)}, which hands
 * every block of samples it can produce to the consumer before returning; a packet may yield no
 * block (while the decoder is priming) or several. {@link #flush(Consumer)} drains whatever the
 * decoder still buffers once the input ends. The {@link AudioBuffer} passed to the consumer is
 * owned and recycled by the decoder: it is valid only until the consumer returns, so a consumer
 * that keeps samples copies them.
 *
 * <p>{@link #sampleRate()} and {@link #layout()} report the format of the first block; a stream
 * that changes format mid-way (a concatenated MP3, for example) is reported through the
 * {@link AudioBuffer#sampleRate()} and {@link AudioBuffer#layout()} of each block.
 *
 * @implSpec
 * Implementations must raise {@link MediaException.Malformed} for a corrupt packet rather than
 * any unchecked runtime exception, and must not retain the packet's array after
 * {@link #decode(Packet, Consumer)} returns unless they copied it.
 */
public interface AudioDecoder extends AutoCloseable {
    /**
     * Returns the sampling rate of the decoded output.
     *
     * @return the rate in hertz
     */
    int sampleRate();

    /**
     * Returns the channel layout of the decoded output.
     *
     * @return the layout
     */
    ChannelLayout layout();

    /**
     * Decodes one packet and hands every block of samples it completes to {@code output}.
     *
     * @param packet the next packet in decoding order
     * @param output receives zero or more blocks, each valid only during the call
     * @throws MediaException.Malformed   if the packet is corrupt
     * @throws MediaException.Unsupported if the packet uses a coding tool this decoder lacks
     */
    void decode(Packet packet, Consumer<AudioBuffer> output);

    /**
     * Hands every block the decoder still buffers to {@code output} after the last packet.
     *
     * @param output receives zero or more blocks, each valid only during the call
     */
    void flush(Consumer<AudioBuffer> output);

    /**
     * Releases the decoder's resources.
     *
     * <p>The default implementation holds none and does nothing.
     */
    @Override
    default void close() {
    }
}
