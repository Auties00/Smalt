package com.github.auties00.smalt.video;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.provider.Artifacts;
import com.github.auties00.smalt.internal.provider.Providers;
import com.github.auties00.smalt.spi.VideoCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.TrackKind;

import java.util.Objects;

/**
 * Creates a video encoder for a codec.
 *
 * <p>The encoder comes from the first {@link VideoCodecProvider} that encodes the codec, which
 * maps the codec-independent {@link VideoEncoderOptions} onto the codec's own configuration. The
 * Smalt artifacts encode H.264 ({@code smalt-mpeg}), VP8 and Motion-JPEG ({@code smalt-image}),
 * and VP9, AV1, ProRes and DNxHD ({@code smalt-video}); a codec no provider encodes raises
 * {@link MediaException.Unsupported} naming it and, when a Smalt artifact encodes it, the artifact
 * to add.
 *
 * @apiNote The registry suits callers that pick a codec at run time. A caller that needs a codec's own parameters
 * (profile, rate control, quantizer window, temporal layers) constructs that codec's encoder directly.
 */
public final class VideoEncoders {
    /**
     * Prevents instantiation.
     */
    private VideoEncoders() {
        throw new AssertionError();
    }

    /**
     * Returns whether a codec can be encoded with the artifacts present at run time.
     *
     * @param codec the codec to look up
     * @return {@code true} when {@link #create(Codec, VideoEncoderOptions)} accepts it
     */
    public static boolean isSupported(Codec codec) {
        return provider(Objects.requireNonNull(codec, "codec")) != null;
    }

    /**
     * Creates an encoder for a codec.
     *
     * @param codec the codec to produce
     * @param options the picture size, frame rate, bitrate, tuning and thread count
     * @return the encoder, which the caller closes
     * @throws IllegalArgumentException if {@code codec} is not a video codec, or the codec cannot code the picture size
     * or bitrate
     * @throws MediaException.Unsupported if the codec is not encodable
     */
    public static VideoEncoder create(Codec codec, VideoEncoderOptions options) {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(options, "options");
        if (codec.kind() != TrackKind.VIDEO) {
            throw new IllegalArgumentException(codec.displayName() + " is not a video codec");
        }
        var provider = provider(codec);
        if (provider == null) {
            throw Artifacts.unsupported(codec.displayName(), "encodable", Artifacts.encoderOf(codec));
        }
        return provider.createEncoder(codec, options);
    }

    /**
     * Finds the provider that encodes a codec.
     *
     * @param codec the codec to look up
     * @return the first provider whose {@link VideoCodecProvider#encodes(Codec)} accepts {@code codec}, or {@code null}
     * when none does
     */
    private static VideoCodecProvider provider(Codec codec) {
        for (var provider : Providers.video()) {
            if (provider.encodes(codec)) {
                return provider;
            }
        }
        return null;
    }
}
