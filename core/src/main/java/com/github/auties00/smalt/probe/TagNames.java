package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.metadata.StandardTag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Normalizes the container-specific names of metadata tags to one vocabulary.
 *
 * <p>The common tags get the lower-case keys of {@link StandardTag}, the names ffprobe and the
 * MP4 {@code ilst} reader use: {@code title}, {@code artist}, {@code album}, {@code album_artist},
 * {@code composer}, {@code genre}, {@code date}, {@code track}, {@code disc}, {@code comment},
 * {@code copyright}, {@code encoder}, {@code encoded_by}, {@code publisher}, {@code language},
 * {@code lyrics}, {@code grouping}, {@code creation_time} and {@code compilation}, whatever spelled
 * them: ID3v2 frame identifiers ({@code TIT2}, {@code TPE1}, ...), RIFF {@code INFO} identifiers
 * ({@code INAM}, {@code IART}, ...), AIFF chunk names, and Vorbis comment or Matroska tag names in
 * any case ({@code TITLE}, {@code ALBUMARTIST}, {@code TRACKNUMBER}, ...). An ID3v2 {@code TXXX}
 * frame is named by its description. Every other tag keeps its name. When two names map to the
 * same normalized name, the first wins.
 */
final class TagNames {
    /**
     * The prefix of the names the ID3v2 reader gives {@code TXXX} frames.
     */
    private static final String USER_TEXT_PREFIX = "TXXX:";

    /**
     * Prevents instantiation.
     */
    private TagNames() {
        throw new AssertionError();
    }

    /**
     * Normalizes the names of a tag map.
     *
     * @param tags the tags as the container names them
     * @return the tags with normalized names, in the original order; unmodifiable
     */
    static Map<String, String> normalize(Map<String, String> tags) {
        var result = new LinkedHashMap<String, String>();
        for (var entry : tags.entrySet()) {
            var key = entry.getKey();
            var normalized = StandardTag.fromName(key).map(StandardTag::key).orElse(null);
            if (normalized == null && key.toUpperCase(Locale.ROOT).startsWith(USER_TEXT_PREFIX)) {
                normalized = key.substring(USER_TEXT_PREFIX.length());
            }
            result.putIfAbsent(normalized != null ? normalized : key, entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }
}
