package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.provider.Artifacts;
import com.github.auties00.smalt.internal.provider.Providers;
import com.github.auties00.smalt.spi.AudioCodecProvider;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;

import java.util.Objects;

/**
 * Creates the decoder for an audio track.
 *
 * <p>The decoder comes from the first {@link AudioCodecProvider} that decodes the track's codec.
 * The Smalt artifacts decode AAC (the LC core of every AAC profile), AMR-NB and AMR-WB
 * ({@code smalt-mpeg}), MP3, MP2, Opus, Vorbis and FLAC ({@code smalt-audio}) and every
 * uncompressed PCM variant (core); a
 * codec no provider decodes raises {@link MediaException.Unsupported} naming it and, when a
 * Smalt artifact decodes it, the artifact to add.
 */
public final class AudioDecoders {
    /**
     * Prevents instantiation.
     */
    private AudioDecoders() {
        throw new AssertionError();
    }

    /**
     * Returns whether a codec can be decoded with the artifacts present at run time.
     *
     * @param codec the codec
     * @return {@code true} when {@link #create(AudioTrack, AudioDecoderOptions)} accepts tracks of
     *         it
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public static boolean isSupported(Codec codec) {
        return provider(Objects.requireNonNull(codec, "codec")) != null;
    }

    /**
     * Creates a decoder for a track at its native output rate under the default limits.
     *
     * @param track the track to decode
     * @return the decoder, which the caller closes
     * @throws NullPointerException       if {@code track} is {@code null}
     * @throws MediaException.Unsupported if the codec is not decodable
     * @throws MediaException.Malformed   if the track's configuration is corrupt
     */
    public static AudioDecoder create(AudioTrack track) {
        return create(track, AudioDecoderOptions.defaults());
    }

    /**
     * Creates a decoder for a track.
     *
     * <p>Only Opus decodes natively at other rates (8, 12, 16, 24 or 48 kHz); every other codec
     * ignores {@link AudioDecoderOptions#preferredSampleRate()} and outputs its native rate, so
     * callers still check {@link AudioDecoder#sampleRate()}.
     *
     * @param track   the track to decode
     * @param options the limits and the preferred output rate
     * @return the decoder, which the caller closes
     * @throws NullPointerException       if an argument is {@code null}
     * @throws MediaException.Unsupported if the codec is not decodable
     * @throws MediaException.Malformed   if the track's configuration is corrupt
     */
    public static AudioDecoder create(AudioTrack track, AudioDecoderOptions options) {
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
     * @param codec the codec
     * @return the first provider whose {@link AudioCodecProvider#decodes(Codec)} accepts
     *         {@code codec}, or {@code null} when none does
     */
    private static AudioCodecProvider provider(Codec codec) {
        for (var provider : Providers.audio()) {
            if (provider.decodes(codec)) {
                return provider;
            }
        }
        return null;
    }
}
