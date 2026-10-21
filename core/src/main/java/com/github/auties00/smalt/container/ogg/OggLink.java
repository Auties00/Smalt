package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One link of a chained Ogg file: a group of logical streams that starts with their
 * beginning-of-stream pages and plays after the link before it.
 *
 * <p>A file that is not chained has one link. Times are on the presentation timeline of the whole
 * file, the one packet timestamps follow, so the first link starts at zero and each later link
 * starts where the one before it ends.
 *
 * @param startMicros the time the link starts at, in microseconds
 * @param durationMicros the duration of the link, in microseconds, or {@code -1} when unknown
 * @param trackIds the identifiers of the link's tracks, in track order; unmodifiable
 * @param metadata the comment tags of the link's streams, chapter and picture fields left out, a
 *                 name repeated across streams joining its values with {@code "; "}; unmodifiable
 * @param chapters the chapters of the link's comment headers ({@code CHAPTERxxx} fields), in start
 *                 order, the last ending where the link ends; unmodifiable
 * @param pictures the pictures of the link's comment headers ({@code METADATA_BLOCK_PICTURE}
 *                 fields, and the {@code PICTURE} blocks of Ogg FLAC), in header order; unmodifiable
 */
public record OggLink(long startMicros, long durationMicros, List<Integer> trackIds, Map<String, String> metadata,
                      List<Chapter> chapters, List<AttachedPicture> pictures) {
    /**
     * Validates and copies the components.
     *
     * @throws IllegalArgumentException if {@code startMicros} is negative or {@code durationMicros}
     *                                  is below {@code -1}
     */
    public OggLink {
        if (startMicros < 0 || durationMicros < -1) {
            throw new IllegalArgumentException("invalid link span " + startMicros + " + " + durationMicros);
        }
        trackIds = List.copyOf(Objects.requireNonNull(trackIds, "trackIds"));
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(metadata, "metadata")));
        chapters = List.copyOf(Objects.requireNonNull(chapters, "chapters"));
        pictures = List.copyOf(Objects.requireNonNull(pictures, "pictures"));
    }
}
