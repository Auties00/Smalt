package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.internal.provider.Artifacts;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.transcode.OutputContainer;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Holds the checks the audio and video writers share: the codec a file gets, the declared
 * language and the tags.
 */
public final class WriterSupport {
    private WriterSupport() {
        throw new AssertionError();
    }

    /**
     * Checks an ISO 639-2 language code.
     *
     * @param language the code, or {@code null} for none
     * @return {@code language}
     * @throws IllegalArgumentException if the code is not three lowercase ASCII letters
     */
    public static String language(String language) {
        if (language != null && (language.length() != 3 || !language.chars().allMatch(c -> c >= 'a' && c <= 'z'))) {
            throw new IllegalArgumentException("language " + language + " is not an ISO 639-2 code");
        }
        return language;
    }

    /**
     * Picks the codec of a track written to a file profile.
     *
     * @param requested the codec the caller asked for, or empty for the profile's first encodable default
     * @param container the file profile
     * @param defaults the profile's default codecs of the track's kind, in order of preference
     * @param encodable whether an encoder is available for a codec
     * @param kind the kind of the track, for messages
     * @return the codec
     * @throws IllegalArgumentException if the profile cannot carry the requested codec, or carries no
     * track of the kind
     * @throws MediaException.Unsupported if no encoder is available for the codec
     */
    public static Codec codec(Optional<Codec> requested, OutputContainer container, List<Codec> defaults,
                              Predicate<Codec> encodable, String kind) {
        if (requested.isPresent()) {
            var codec = requested.get();
            if (!container.supports(codec)) {
                throw new IllegalArgumentException(container + " files cannot carry " + codec.displayName());
            }
            if (!encodable.test(codec)) {
                throw Artifacts.unsupported(codec.displayName(), "encodable", Artifacts.encoderOf(codec));
            }
            return codec;
        }
        for (var codec : defaults) {
            if (encodable.test(codec)) {
                return codec;
            }
        }
        if (defaults.isEmpty()) {
            throw new IllegalArgumentException(container + " files cannot carry " + kind);
        }
        var first = defaults.getFirst();
        throw Artifacts.unsupported(first.displayName(), "encodable", Artifacts.encoderOf(first));
    }

    /**
     * Picks the codec of a track written to a caller's muxer.
     *
     * @param requested the codec the caller asked for
     * @param encodable whether an encoder is available for a codec
     * @return the codec
     * @throws IllegalArgumentException if no codec was requested
     * @throws MediaException.Unsupported if no encoder is available for the codec
     */
    public static Codec codec(Optional<Codec> requested, Predicate<Codec> encodable) {
        var codec = requested.orElseThrow(() -> new IllegalArgumentException("a writer over a muxer needs a codec"));
        if (!encodable.test(codec)) {
            throw Artifacts.unsupported(codec.displayName(), "encodable", Artifacts.encoderOf(codec));
        }
        return codec;
    }

    /**
     * Checks that a muxer can store tags before anything is written.
     *
     * @param muxer the muxer about to receive the track
     * @param tags the tags, which need no support when empty
     * @throws MediaException.Unsupported if the tags are not empty and the muxer cannot store tags
     */
    public static void requireTags(Muxer muxer, MediaTags tags) {
        if (!tags.isEmpty() && !muxer.supportsTags()) {
            throw new MediaException.Unsupported("tags", muxer.getClass().getSimpleName() + " cannot store tags");
        }
    }

    /**
     * Writes tags to a muxer whose track is declared.
     *
     * @param muxer the muxer whose track is declared
     * @param tags the tags, which are not written when empty
     */
    public static void writeTags(Muxer muxer, MediaTags tags) {
        if (!tags.isEmpty()) {
            muxer.setTags(tags);
        }
    }
}
