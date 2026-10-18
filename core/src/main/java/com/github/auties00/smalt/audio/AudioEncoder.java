package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes planar float samples into the packets of one audio track.
 *
 * <p>{@link #encode(AudioBuffer, Consumer)} accepts blocks of any length: the encoder buffers
 * samples internally and emits one packet per complete codec frame of {@link #frameSize()}
 * samples, so callers never have to align their input to the codec's frame size.
 * {@link #flush(Consumer)} pads and encodes the final partial frame and drains every delayed
 * packet. Packet timestamps are in units of {@code 1 / sampleRate()} and start at zero for the
 * first input sample; the {@link #delay()} priming samples at the start of the decoded output
 * are signaled to the container rather than subtracted from the timestamps.
 *
 * @implSpec
 * Implementations must accept blocks whose layout and rate match {@link #layout()} and
 * {@link #sampleRate()} and reject any other with {@link IllegalArgumentException}, must clip
 * samples outside {@code [-1, 1]} rather than wrap them, and must not retain the input buffer.
 */
public interface AudioEncoder extends AutoCloseable {
    /**
     * Returns the coding format produced.
     *
     * @return the codec
     */
    Codec codec();

    /**
     * Returns the sampling rate the encoder accepts and codes at.
     *
     * @return the rate in hertz
     */
    int sampleRate();

    /**
     * Returns the channel layout the encoder accepts.
     *
     * @return the layout
     */
    ChannelLayout layout();

    /**
     * Returns the number of samples per channel in one coded frame.
     *
     * @return the frame size
     */
    int frameSize();

    /**
     * Returns the number of priming samples per channel a decoder outputs before the first input
     * sample.
     *
     * @return the encoder delay, non-negative
     */
    int delay();

    /**
     * Returns the out-of-band decoder configuration a container must store for this stream.
     *
     * @return the configuration in the form described by
     *         {@link com.github.auties00.smalt.track.Track#codecConfig()}, or empty when
     *         the codec needs none
     */
    Optional<byte[]> codecConfig();

    /**
     * Buffers {@code input} and emits a packet for every coded frame it completes.
     *
     * @param input  the samples to encode, of any length
     * @param output receives zero or more packets
     * @throws IllegalArgumentException if the input layout or rate differs from the encoder's
     */
    void encode(AudioBuffer input, Consumer<Packet> output);

    /**
     * Encodes the buffered partial frame, padded with silence, and emits every remaining packet.
     *
     * @param output receives zero or more packets
     */
    void flush(Consumer<Packet> output);

    /**
     * Releases the encoder's resources.
     *
     * <p>The default implementation holds none and does nothing.
     */
    @Override
    default void close() {
    }
}
