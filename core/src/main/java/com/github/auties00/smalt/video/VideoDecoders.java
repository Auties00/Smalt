package com.github.auties00.smalt.video;

import com.github.auties00.smalt.MediaException;
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
 * The Smalt artifacts decode H.264 (progressive and interlaced 4:2:0, 4:2:2 and 4:0:0 of 8 to 14
 * bits, Baseline through High 4:2:2, {@code smalt-mpeg}), VP8 ({@code smalt-image}), VP9 (every profile)
 * and AV1 (8-, 10- and 12-bit 4:2:0, 4:2:2, 4:4:4 and monochrome, {@code smalt-video}); a codec
 * no provider decodes raises
 * {@link MediaException.Unsupported} naming it and, when a Smalt artifact decodes it, the
 * artifact to add. A stream using a tool its decoder lacks raises
 * {@link MediaException.Unsupported} naming the tool.
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
     * @throws MediaException.Unsupported if the codec is not decodable
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
     * @throws MediaException.Unsupported if the codec is not decodable
     * @throws MediaException.Malformed if the track's configuration is corrupt
     */
    public static VideoDecoder create(VideoTrack track, VideoDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        var codec = track.codec();
        var provider = provider(codec);
        if (provider == null) {
            throw Artifacts.unsupported(codec.displayName(), "decodable", Artifacts.decoderOf(codec));
        }
        return provider.createDecoder(track, options);
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
