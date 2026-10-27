package com.github.auties00.smalt.container;

import java.util.List;
import java.util.Objects;

/**
 * Groups the chapters of one version of a presentation, such as the theatrical and the director's
 * cut of a film, as a Matroska {@code EditionEntry} does.
 *
 * <p>Every other format stores a single edition. When no edition of a list is flagged as the
 * default, the first one is.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param chapters the top-level chapters, in container order
 * @param defaultEdition whether the container flags this edition as the one to use by default
 * @param ordered whether the chapters are ordered chapters, which play in their stored order and
 * skip the parts of the presentation they do not cover, rather than marks on its timeline; a
 * demuxer that presents the timeline an ordered edition defines reports that edition with its
 * chapters on that timeline, not ordered
 * @param names the name of the edition in each language the container stores, in container order
 */
public record ChapterEdition(List<Chapter> chapters, boolean defaultEdition, boolean ordered,
                             List<Chapter.Title> names) {
    /**
     * Validates the edition.
     *
     * @throws NullPointerException if an element of {@code chapters} or {@code names} is {@code null}
     */
    public ChapterEdition {
        chapters = List.copyOf(chapters);
        names = List.copyOf(names);
    }

    /**
     * Creates an edition that is not flagged as the default, orders nothing and has no name.
     *
     * @param chapters the chapters, in order
     * @return the edition
     * @throws NullPointerException if an element is {@code null}
     */
    public static ChapterEdition of(List<Chapter> chapters) {
        return new ChapterEdition(chapters, false, false, List.of());
    }

    /**
     * Returns a copy of this edition with other chapters.
     *
     * @param chapters the chapters, in order
     * @return the edition
     * @throws NullPointerException if an element is {@code null}
     */
    public ChapterEdition withChapters(List<Chapter> chapters) {
        return new ChapterEdition(chapters, defaultEdition, ordered, names);
    }

    /**
     * Returns the edition a player uses by default: the first one flagged as the default, or the first one.
     *
     * @param editions the editions, in container order
     * @return the index of the default edition, or {@code -1} when the list is empty
     */
    public static int defaultIndex(List<ChapterEdition> editions) {
        Objects.requireNonNull(editions, "editions");
        for (var index = 0; index < editions.size(); index++) {
            if (editions.get(index).defaultEdition()) {
                return index;
            }
        }
        return editions.isEmpty() ? -1 : 0;
    }
}
