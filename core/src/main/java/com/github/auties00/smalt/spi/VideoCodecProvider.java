package com.github.auties00.smalt.spi;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoEncoder;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;

import java.util.ServiceLoader;

/**
 * Creates the decoders and encoders of one or more video codecs on behalf of
 * {@link VideoDecoders} and {@link VideoEncoders}.
 *
 * <p>{@link VideoDecoders} hands a track to the first provider whose {@link #decodes(Codec)}
 * accepts the track's codec, and {@link VideoEncoders} asks the first provider whose
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
 * {@link #createDecoder(VideoTrack, VideoDecoderOptions)}), the encoding pair
 * ({@link #encodes(Codec)} and {@link #createEncoder(Codec, VideoEncoderOptions)}), or both.
 */
public interface VideoCodecProvider {
    /**
     * Returns whether this provider decodes a codec.
     *
     * @implSpec The default implementation returns {@code false}.
     *
     * @param codec the codec to decode
     * @return {@code true} when {@link #createDecoder(VideoTrack, VideoDecoderOptions)} accepts
     * tracks of {@code codec}
     */
    default boolean decodes(Codec codec) {
        return false;
    }

    /**
     * Creates a decoder for a track.
     *
     * <p>The decoder follows the thread convention of {@link VideoDecoderOptions#threads()} as
     * far as it parallelizes; the output never depends on the thread count.
     *
     * @implSpec The default implementation throws {@link UnsupportedOperationException}; a
     * provider whose {@link #decodes(Codec)} accepts a codec overrides it.
     *
     * @param track the track to decode, of a codec {@link #decodes(Codec)} accepts
     * @param options the limits and the thread count
     * @return the decoder, which the caller closes
     * @throws MediaException.Unsupported if the track uses a feature the decoder lacks
     * @throws MediaException.Malformed if the track's configuration is corrupt
     */
    default VideoDecoder createDecoder(VideoTrack track, VideoDecoderOptions options) {
        throw new UnsupportedOperationException(getClass().getName() + " decodes no codec");
    }

    /**
     * Returns whether this provider encodes a codec.
     *
     * @implSpec The default implementation returns {@code false}.
     *
     * @param codec the codec to produce
     * @return {@code true} when {@link #createEncoder(Codec, VideoEncoderOptions)} accepts
     * {@code codec}
     */
    default boolean encodes(Codec codec) {
        return false;
    }

    /**
     * Returns whether this provider's encoder of a codec codes the alpha planes of the pictures as a
     * second stream when {@link VideoEncoderOptions#alpha()} is set.
     *
     * @implSpec The default implementation returns {@code false}. A provider that returns {@code true}
     * creates, for options asking for alpha, an encoder whose shown packets carry the coded alpha frame in
     * {@link com.github.auties00.smalt.track.PacketSideData#alphaFrame()}, a frame of the same codec whose
     * decoded luma plane is the picture's straight alpha plane, coded so that the alpha stream can be
     * decoded from every key frame of the main stream.
     *
     * @param codec the codec to produce, one {@link #encodes(Codec)} accepts
     * @return {@code true} when {@link #createEncoder(Codec, VideoEncoderOptions)} honors
     * {@link VideoEncoderOptions#alpha()} for {@code codec}
     */
    default boolean encodesAlpha(Codec codec) {
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
     * @param options the picture size, frame rate, bitrate, tuning and thread count
     * @return the encoder, which the caller closes
     * @throws IllegalArgumentException if the codec cannot code the picture size or bitrate
     */
    default VideoEncoder createEncoder(Codec codec, VideoEncoderOptions options) {
        throw new UnsupportedOperationException(getClass().getName() + " encodes no codec");
    }
}
