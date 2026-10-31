package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaException;
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
 * <p>Encoders of codecs that code integer samples (FLAC, integer PCM) also take integer samples
 * ({@link #encode(IntegerAudioBuffer, Consumer)}), which they code without the rounding of a float, and encoders
 * that keep more than single precision (PCM) take 64-bit floats ({@link #encode(DoubleAudioBuffer, Consumer)}).
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
     * Returns the sample or code size the stream's track declares as its
     * {@link com.github.auties00.smalt.track.AudioTrack#bitsPerSample() bitsPerSample}: the code size of an ADPCM
     * codec, which a container must store for a decoder to read the stream.
     *
     * @implSpec The default implementation returns 0.
     * @return the size in bits, or 0 when the track declares none
     */
    default int bitsPerSample() {
        return 0;
    }

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
     * Returns whether the encoder also takes integer samples through
     * {@link #encode(IntegerAudioBuffer, Consumer)}.
     *
     * @implSpec The default implementation returns {@code false}.
     * @return {@code true} for encoders of codecs that code integer samples
     */
    default boolean supportsIntegerInput() {
        return false;
    }

    /**
     * Buffers integer samples and emits a packet for every coded frame they complete.
     *
     * <p>Samples of the encoder's own sample size are coded unchanged. Samples of a smaller size are shifted left to
     * it, which keeps every value; samples of a larger size are rounded to it, to nearest with ties toward positive
     * infinity, and clamped. Integer and float blocks may be mixed in one stream.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}.
     * @param input the samples to encode, of any length
     * @param output receives zero or more packets
     * @throws UnsupportedOperationException if {@link #supportsIntegerInput()} is {@code false}
     * @throws IllegalArgumentException if the input layout or rate differs from the encoder's
     */
    default void encode(IntegerAudioBuffer input, Consumer<Packet> output) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " takes no integer input");
    }

    /**
     * Returns whether the encoder also takes 64-bit float samples through
     * {@link #encode(DoubleAudioBuffer, Consumer)}, coding them at more than single precision.
     *
     * @implSpec The default implementation returns {@code false}.
     * @return {@code true} for encoders that keep more precision than a float carries
     */
    default boolean supportsDoubleInput() {
        return false;
    }

    /**
     * Buffers 64-bit float samples and emits a packet for every coded frame they complete.
     *
     * <p>Samples are clipped and quantized as {@link #encode(AudioBuffer, Consumer)} does, from their double value.
     * Double and float blocks may be mixed in one stream.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}.
     * @param input the samples to encode, of any length
     * @param output receives zero or more packets
     * @throws UnsupportedOperationException if {@link #supportsDoubleInput()} is {@code false}
     * @throws IllegalArgumentException if the input layout or rate differs from the encoder's
     */
    default void encode(DoubleAudioBuffer input, Consumer<Packet> output) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " takes no double input");
    }

    /**
     * Encodes the buffered partial frame, padded with silence, and emits every remaining packet.
     *
     * @param output receives zero or more packets
     */
    void flush(Consumer<Packet> output);

    /**
     * Changes the target bitrate of the frames coded from now on, without restarting the stream.
     *
     * <p>The new target governs every frame the encoder codes after the call, including frames of samples already
     * passed to {@link #encode(AudioBuffer, Consumer)} and still buffered. How quickly the coded rate follows it
     * depends on the codec's rate control; a codec of fixed bitrate steps takes the step each encoder documents.
     *
     * @implSpec The default implementation throws {@link MediaException.Unsupported}; an implementation that can
     * retarget a running stream overrides it.
     * @param bitsPerSecond the new target, in bits per second; positive
     * @throws IllegalArgumentException if {@code bitsPerSecond} is not positive or the codec cannot reach it
     * @throws IllegalStateException if the encoder runs without a target bitrate, for example in a constant-quality
     * mode
     * @throws MediaException.Unsupported if the encoder cannot change its bitrate while running
     */
    default void setTargetBitrate(long bitsPerSecond) {
        throw new MediaException.Unsupported("bitrate change",
                codec().displayName() + " encoder cannot change its bitrate while running");
    }

    /**
     * Releases the encoder's resources.
     *
     * <p>The default implementation holds none and does nothing.
     */
    @Override
    default void close() {
    }
}
