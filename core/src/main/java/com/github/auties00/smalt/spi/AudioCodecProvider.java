package com.github.auties00.smalt.spi;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

import java.util.ServiceLoader;

/**
 * Creates the decoders and encoders of one or more audio codecs on behalf of
 * {@link AudioDecoders} and {@link AudioEncoders}.
 *
 * <p>{@link AudioDecoders} hands a track to the first provider whose {@link #decodes(Codec)}
 * accepts the track's codec, and {@link AudioEncoders} asks the first provider whose
 * {@link #encodes(Codec)} accepts the codec; a codec no provider accepts raises
 * {@link MediaException.Unsupported}. Providers are found once, the first time either registry is
 * used, with {@link ServiceLoader} through the class loader that defined this interface: a named
 * module declares one with {@code provides} in its module declaration, a jar on the class path
 * with a {@code META-INF/services} entry named after this interface. The order in which providers
 * of the same codec are consulted is unspecified, so providers should not claim the same codec.
 *
 * @implSpec Implementations must be thread-safe, since one instance serves every caller of the
 * JVM, and must have a public no-argument constructor that does no work: every provider is
 * instantiated when the providers are first looked up, whatever codec the caller uses. A provider
 * overrides the decoding pair ({@link #decodes(Codec)} and
 * {@link #createDecoder(AudioTrack, AudioDecoderOptions)}), the encoding pair
 * ({@link #encodes(Codec)} and {@link #createEncoder(Codec, AudioEncoderOptions)}), or both.
 */
public interface AudioCodecProvider {
    /**
     * Returns whether this provider decodes a codec.
     *
     * @implSpec The default implementation returns {@code false}.
     *
     * @param codec the codec to decode
     * @return {@code true} when {@link #createDecoder(AudioTrack, AudioDecoderOptions)} accepts
     * tracks of {@code codec}
     */
    default boolean decodes(Codec codec) {
        return false;
    }

    /**
     * Creates a decoder for a track.
     *
     * <p>A codec that decodes natively at several rates decodes at the options' preferred rate
     * when it supports that rate; every other codec ignores the preference and outputs its native
     * rate, so callers check {@link AudioDecoder#sampleRate()}.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}; a
     * provider whose {@link #decodes(Codec)} accepts a codec overrides it.
     *
     * @param track the track to decode, of a codec {@link #decodes(Codec)} accepts
     * @param options the limits and the preferred output rate
     * @return the decoder, which the caller closes
     * @throws MediaException.Unsupported if the track uses a feature the decoder lacks
     * @throws MediaException.Malformed if the track's configuration is corrupt
     */
    default AudioDecoder createDecoder(AudioTrack track, AudioDecoderOptions options) {
        throw new UnsupportedOperationException(getClass().getName() + " decodes no codec");
    }

    /**
     * Returns whether this provider encodes a codec.
     *
     * @implSpec The default implementation returns {@code false}.
     *
     * @param codec the codec to produce
     * @return {@code true} when {@link #createEncoder(Codec, AudioEncoderOptions)} accepts
     * {@code codec}
     */
    default boolean encodes(Codec codec) {
        return false;
    }

    /**
     * Creates an encoder producing a codec.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}; a
     * provider whose {@link #encodes(Codec)} accepts a codec overrides it, mapping the options
     * onto the codec's own configuration.
     *
     * @param codec the codec to produce, one {@link #encodes(Codec)} accepts
     * @param options the input format, bitrate and tuning
     * @return the encoder, which the caller closes
     * @throws IllegalArgumentException if the codec cannot code the input format or bitrate
     */
    default AudioEncoder createEncoder(Codec codec, AudioEncoderOptions options) {
        throw new UnsupportedOperationException(getClass().getName() + " encodes no codec");
    }
}
