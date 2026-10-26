package com.github.auties00.smalt.video;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.codec.AlphaVideoDecoder;
import com.github.auties00.smalt.internal.provider.Artifacts;
import com.github.auties00.smalt.internal.provider.Providers;
import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Objects;

/**
 * Creates the decoder for a video track.
 *
 * <p>The decoder comes from the first {@link VideoCodecProvider} that decodes the track's codec.
 * The Smalt artifacts decode H.264 (progressive and interlaced 4:2:0, 4:2:2, 4:4:4 and 4:0:0 of 8 to
 * 14 bits, lossless included, Baseline through High 4:4:4 Predictive, {@code smalt-mpeg}), VP8
 * ({@code smalt-image}), VP9 (every profile, 4:4:0 included)
 * and AV1 (8-, 10- and 12-bit 4:2:0, 4:2:2, 4:4:4 and monochrome, {@code smalt-video}); a codec
 * no provider decodes raises
 * {@link MediaException.Unsupported} naming it and, when a Smalt artifact decodes it, the
 * artifact to add. A stream using a tool its decoder lacks raises
 * {@link MediaException.Unsupported} naming the tool.
 *
 * <p>A Dolby Vision track is decoded as its base layer: the reference processing units its packets carry are
 * ignored. A track whose {@linkplain VideoTrack#dolbyVision() Dolby Vision configuration} is not
 * {@linkplain com.github.auties00.smalt.track.DolbyVision#isBackwardCompatible() backward compatible} (profile 5)
 * raises {@link MediaException.Unsupported}, since its base layer shows wrong colors as plain video, unless
 * {@link VideoDecoderOptions#dolbyVisionBaseLayer()} asks for the base layer anyway.
 *
 * <p>A track that {@linkplain VideoTrack#hasAlpha() carries alpha} as a second coded stream, as
 * transparent VP8 and VP9 video in WebM does, is decoded with two decoders of its codec: frames come
 * out as {@link com.github.auties00.smalt.image.YuvPicture}s or
 * {@link com.github.auties00.smalt.image.Yuv16Picture}s whose alpha plane is the luma plane of the
 * {@linkplain com.github.auties00.smalt.track.PacketSideData#alphaFrame() alpha frame} their packet
 * carries, rescaled to the frame's bit depth when the two differ. A frame whose packet carries no alpha
 * frame, or one the second decoder rejects as corrupt or decodes to another size, is opaque.
 */
public final class VideoDecoders {
    /**
     * Prevents instantiation.
     */
    private VideoDecoders() {
        throw new AssertionError();
    }

    /**
     * Returns whether a codec can be decoded with the artifacts present at run time.
     *
     * @param codec the codec to look up
     * @return {@code true} when {@link #create(VideoTrack, VideoDecoderOptions)} accepts tracks of it
     */
    public static boolean isSupported(Codec codec) {
        return provider(Objects.requireNonNull(codec, "codec")) != null;
    }

    /**
     * Creates a decoder for a track with {@link VideoDecoderOptions#defaults()}.
     *
     * @param track the track to decode
     * @return the decoder, which the caller closes
     * @throws MediaException.Unsupported if the codec is not decodable, or the track is a Dolby Vision track without
     * a backward compatible base layer and the options do not ask for its base layer
     * @throws MediaException.Malformed if the track's configuration is corrupt
     */
    public static VideoDecoder create(VideoTrack track) {
        return create(track, VideoDecoderOptions.defaults());
    }

    /**
     * Creates a decoder for a track.
     *
     * <p>The output does not depend on {@link VideoDecoderOptions#threads()}.
     *
     * @param track the track to decode
     * @param options the limits and the thread count
     * @return the decoder, which the caller closes
     * @throws MediaException.Unsupported if the codec is not decodable, or the track is a Dolby Vision track without
     * a backward compatible base layer and the options do not ask for its base layer
     * @throws MediaException.Malformed if the track's configuration is corrupt
     */
    public static VideoDecoder create(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        var codec = track.codec();
        var dolbyVision = track.dolbyVision().orElse(null);
        if (dolbyVision != null && !dolbyVision.isBackwardCompatible() && !options.dolbyVisionBaseLayer()) {
            // TODO: reconstruct the pictures of non-backward-compatible Dolby Vision streams from their RPUs
            throw new MediaException.Unsupported("dolby vision reconstruction", "Dolby Vision profile "
                    + dolbyVision.profile() + " without a backward compatible base layer is not decodable; set"
                    + " VideoDecoderOptions.dolbyVisionBaseLayer to decode its base layer as plain video");
        }
        var provider = provider(codec);
        if (provider == null) {
            throw Artifacts.unsupported(codec.displayName(), "decodable", Artifacts.decoderOf(codec));
        }
        var decoder = provider.createDecoder(track, options);
        if (!track.hasAlpha()) {
            return decoder;
        }
        try {
            return new AlphaVideoDecoder(decoder, provider.createDecoder(track.toBuilder().alpha(false).color(null).build(),
                    options));
        } catch (RuntimeException exception) {
            decoder.close();
            throw exception;
        }
    }

    /**
     * Finds the provider that decodes a codec.
     *
     * @param codec the codec to look up
     * @return the first provider whose {@link VideoCodecProvider#decodes(Codec)} accepts {@code codec}, or {@code null}
     * when none does
     */
    private static VideoCodecProvider provider(Codec codec) {
        for (var provider : Providers.video()) {
            if (provider.decodes(codec)) {
                return provider;
            }
        }
        return null;
    }
}
