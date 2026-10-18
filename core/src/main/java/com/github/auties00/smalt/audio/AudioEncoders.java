package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.provider.Artifacts;
import com.github.auties00.smalt.internal.provider.Providers;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.TrackKind;

import java.util.Objects;

/**
 * Creates an audio encoder for a codec.
 *
 * <p>The encoder comes from the first {@link AudioCodecProvider} that encodes the codec, which
 * maps the codec-independent {@link AudioEncoderOptions} onto the codec's own configuration. The
 * Smalt artifacts encode PCM ({@code smalt-core}, every {@code PCM_*} format), Opus, FLAC, MP3
 * and Vorbis ({@code smalt-audio}), and AAC-LC, AMR-NB and AMR-WB ({@code smalt-mpeg}); a codec
 * no provider encodes raises {@link MediaException.Unsupported} naming it and, when a Smalt
 * artifact encodes it, the artifact to add.
 *
 * @apiNote The registry suits callers that pick a codec at run time. A caller that needs a codec's
 *          own parameters (Opus complexity or frame duration, AAC variable bitrate) constructs that
 *          codec's encoder directly.
 */
public final class AudioEncoders {
    /**
     * Prevents instantiation.
     */
    private AudioEncoders() {
        throw new AssertionError();
    }

    /**
     * Returns whether a codec can be encoded with the artifacts present at run time.
     *
     * @param codec the codec
     * @return {@code true} when {@link #create(Codec, AudioEncoderOptions)} accepts it
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public static boolean isSupported(Codec codec) {
        return provider(Objects.requireNonNull(codec, "codec")) != null;
    }

    /**
     * Creates an encoder for 48 kHz stereo input at the codec's default bitrate.
     *
     * @param codec the codec to produce
     * @return the encoder, which the caller closes
     * @throws NullPointerException       if {@code codec} is {@code null}
     * @throws IllegalArgumentException   if {@code codec} is not an audio codec
     * @throws MediaException.Unsupported if the codec is not encodable
     */
    public static AudioEncoder create(Codec codec) {
        return create(codec, AudioEncoderOptions.defaults());
    }

    /**
     * Creates an encoder for a codec.
     *
     * @param codec   the codec to produce
     * @param options the input format, bitrate and tuning
     * @return the encoder, which the caller closes
     * @throws NullPointerException       if an argument is {@code null}
     * @throws IllegalArgumentException   if {@code codec} is not an audio codec, or the codec cannot
     *                                    code the input format or bitrate
     * @throws MediaException.Unsupported if the codec is not encodable
     */
    public static AudioEncoder create(Codec codec, AudioEncoderOptions options) {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(options, "options");
        if (codec.kind() != TrackKind.AUDIO) {
            throw new IllegalArgumentException(codec.displayName() + " is not an audio codec");
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
     * @param codec the codec
     * @return the first provider whose {@link AudioCodecProvider#encodes(Codec)} accepts
     *         {@code codec}, or {@code null} when none does
     */
    private static AudioCodecProvider provider(Codec codec) {
        for (var provider : Providers.audio()) {
            if (provider.encodes(codec)) {
                return provider;
            }
        }
        return null;
    }
}
