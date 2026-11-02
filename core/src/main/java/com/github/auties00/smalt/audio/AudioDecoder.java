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
 * <p>Decoders of codecs that code integer samples (FLAC, ALAC, integer PCM) also output those samples bit for bit
 * ({@link #decodeIntegers(Packet, Consumer)}), and decoders whose samples a double holds exactly also output 64-bit
 * floats ({@link #decodeDoubles(Packet, Consumer)}); the float output rounds samples wider than 24 bits.
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
     * @throws MediaException.Malformed if the stream fails the integrity check that
     * {@link AudioDecoderOptions#verifyIntegrity()} requested
     */
    void flush(Consumer<AudioBuffer> output);

    /**
     * Returns whether the decoder also outputs the stream's integer samples, bit for bit, through
     * {@link #decodeIntegers(Packet, Consumer)}.
     *
     * @implSpec The default implementation returns {@code false}.
     * @return {@code true} for codecs that code integer samples
     */
    default boolean supportsIntegerOutput() {
        return false;
    }

    /**
     * Returns the sample size of the stream's integer samples as the stream declares it before the first block.
     *
     * <p>A stream may change its sample size later; each {@link IntegerAudioBuffer} reports its own.
     *
     * @implSpec The default implementation returns 0.
     * @return the size in bits, from 1 to 32, or 0 when the decoder has no integer output or the stream declares no
     * size before its first block
     */
    default int bitsPerSample() {
        return 0;
    }

    /**
     * Decodes one packet into the stream's integer samples and hands every block it completes to {@code output}.
     *
     * <p>The blocks hold the samples of {@link #decode(Packet, Consumer)}'s blocks, in the same number and order,
     * before their scaling to floats. A decoder is driven through one output form for the whole stream: its
     * integer blocks end with {@link #flushIntegers(Consumer)}.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}.
     * @param packet the next packet in decoding order
     * @param output receives zero or more blocks, each valid only during the call
     * @throws UnsupportedOperationException if {@link #supportsIntegerOutput()} is {@code false}
     * @throws MediaException.Malformed if the packet is corrupt
     * @throws MediaException.Unsupported if the packet uses a coding tool this decoder lacks
     */
    default void decodeIntegers(Packet packet, Consumer<IntegerAudioBuffer> output) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " has no integer output");
    }

    /**
     * Hands every integer block the decoder still buffers to {@code output} after the last packet.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}.
     * @param output receives zero or more blocks, each valid only during the call
     * @throws UnsupportedOperationException if {@link #supportsIntegerOutput()} is {@code false}
     * @throws MediaException.Malformed if the stream fails the integrity check that
     * {@link AudioDecoderOptions#verifyIntegrity()} requested
     */
    default void flushIntegers(Consumer<IntegerAudioBuffer> output) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " has no integer output");
    }

    /**
     * Returns whether the decoder also outputs 64-bit float samples through
     * {@link #decodeDoubles(Packet, Consumer)}, exact for 64-bit float samples and for integer
     * samples of up to 53 bits.
     *
     * @implSpec The default implementation returns {@code false}.
     * @return {@code true} for codecs of integer or floating-point samples that keep them exactly
     */
    default boolean supportsDoubleOutput() {
        return false;
    }

    /**
     * Decodes one packet into 64-bit float samples and hands every block it completes to {@code output}.
     *
     * <p>The blocks hold the samples of {@link #decode(Packet, Consumer)}'s blocks, in the same number and order,
     * at double precision: 64-bit float samples unchanged, and integer samples scaled by
     * {@code 2^-(bitsPerSample - 1)} exactly up to 53 bits. A decoder is driven through one output form for the
     * whole stream: its double blocks end with {@link #flushDoubles(Consumer)}.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}.
     * @param packet the next packet in decoding order
     * @param output receives zero or more blocks, each valid only during the call
     * @throws UnsupportedOperationException if {@link #supportsDoubleOutput()} is {@code false}
     * @throws MediaException.Malformed if the packet is corrupt
     * @throws MediaException.Unsupported if the packet uses a coding tool this decoder lacks
     */
    default void decodeDoubles(Packet packet, Consumer<DoubleAudioBuffer> output) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " has no double output");
    }

    /**
     * Hands every double block the decoder still buffers to {@code output} after the last packet.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}.
     * @param output receives zero or more blocks, each valid only during the call
     * @throws UnsupportedOperationException if {@link #supportsDoubleOutput()} is {@code false}
     * @throws MediaException.Malformed if the stream fails the integrity check that
     * {@link AudioDecoderOptions#verifyIntegrity()} requested
     */
    default void flushDoubles(Consumer<DoubleAudioBuffer> output) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " has no double output");
    }

    /**
     * Returns whether the decoder can stand in for lost packets through {@link #conceal(int, Consumer)}.
     *
     * @implSpec The default implementation returns {@code false}.
     * @return {@code true} for decoders that synthesize a replacement for a span of lost packets
     */
    default boolean supportsConcealment() {
        return false;
    }

    /**
     * Conceals a span of lost packets and hands the samples that replace it to {@code output}.
     *
     * <p>A receiver that never got part of the stream, for example the packets of an RTP timestamp gap, calls this
     * method in place of {@link #decode(Packet, Consumer)} for that span, then goes on decoding the packets that
     * follow it. The decoder synthesizes the span from the signal decoded before it, fades a long span to silence,
     * and leaves its state so that the next packet continues without a discontinuity; packets that refer to the
     * lost ones (prediction, a bit reservoir) decode as well as the missing data allows. Before the first packet
     * the replacement is silence.
     *
     * <p>The span is produced in whole concealment units of the codec, the nearest whole number of them and at
     * least one: the frame length for codecs of fixed frame length, 2.5 ms for Opus, half the last block for
     * Vorbis and one sample for uncompressed and lossless codecs; each decoder documents its unit. The blocks have
     * the format of the last decoded block, or {@link #sampleRate()} and {@link #layout()} before the first. The
     * output is deterministic: it depends only on the packets decoded before and on {@code samples}.
     *
     * @implSpec The default implementation throws {@link MediaException.Unsupported}.
     * @param samples the duration of the lost span in samples per channel at the output rate, positive
     * @param output receives one or more blocks, each valid only during the call
     * @return the number of samples per channel handed to {@code output}
     * @throws IllegalArgumentException if {@code samples} is not positive
     * @throws MediaException.Unsupported if {@link #supportsConcealment()} is {@code false}
     */
    default int conceal(int samples, Consumer<AudioBuffer> output) {
        throw new MediaException.Unsupported("packet loss concealment",
                getClass().getSimpleName() + " cannot conceal lost packets");
    }

    /**
     * Releases the decoder's resources.
     *
     * <p>The default implementation holds none and does nothing.
     */
    @Override
    default void close() {
    }
}
