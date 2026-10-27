package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.track.TrackDisposition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the track tags model: its validation, its name, its copies, and how {@link MediaTags} holds one per track.
 */
@DisplayName("TrackTags")
class TrackTagsTest {
    @Test
    @DisplayName("names the track by its title and copies its dispositions")
    void name() {
        var dispositions = new HashSet<TrackDisposition>(Set.of(TrackDisposition.DEFAULT));
        var track = new TrackTags(3, MediaTags.builder().set(StandardTag.TITLE, "Main").set("comment", "c").build(),
                dispositions);
        dispositions.add(TrackDisposition.FORCED);
        assertEquals(Optional.of("Main"), track.name());
        assertEquals(Set.of(TrackDisposition.DEFAULT), track.disposition());
        assertTrue(track.has(TrackDisposition.DEFAULT));
        assertFalse(track.has(TrackDisposition.FORCED));
        assertThrows(UnsupportedOperationException.class, () -> track.disposition().add(TrackDisposition.DUB));
        assertEquals(Optional.of("Other"), track.withName("Other").name());
        assertEquals("c", track.withName("Other").tags().get("comment").orElseThrow());
        var commented = new TrackTags(3, MediaTags.builder().set("comment", "c").build(), Set.of());
        assertEquals(List.of(new MediaTags.Entry("title", "Other"), new MediaTags.Entry("comment", "c")),
                commented.withName("Other").tags().entries(), "the name comes first, as the containers read it");
        assertTrue(track.withName(null).name().isEmpty());
        assertEquals(EnumSet.of(TrackDisposition.COMMENTARY), track.withDisposition(Set.of(TrackDisposition.COMMENTARY))
                .disposition());
        assertEquals(TrackTags.of(3, "Main", TrackDisposition.DEFAULT, TrackDisposition.DEFAULT),
                TrackTags.of(3, "Main", TrackDisposition.DEFAULT));
        assertTrue(TrackTags.of(1).tags().isEmpty() && TrackTags.of(1).disposition().isEmpty());
    }

    @Test
    @DisplayName("rejects pictures, chapters and nested track tags")
    void validation() {
        var picture = MediaTags.builder().addPicture(AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/png", "",
                new byte[]{1})).build();
        assertThrows(IllegalArgumentException.class, () -> new TrackTags(1, picture, Set.of()));
        var chapter = MediaTags.builder().addChapter(Chapter.of(0, 1, "c")).build();
        assertThrows(IllegalArgumentException.class, () -> new TrackTags(1, chapter, Set.of()));
        var nested = MediaTags.builder().track(TrackTags.of(2)).build();
        assertThrows(IllegalArgumentException.class, () -> new TrackTags(1, nested, Set.of()));
        var withNull = new HashSet<TrackDisposition>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new TrackTags(1, MediaTags.empty(), withNull));
    }

    @Test
    @DisplayName("keeps one track tags per identifier in MediaTags, in the order they were added")
    void mediaTags() {
        var tags = MediaTags.builder().track(TrackTags.of(2, "Two")).track(TrackTags.of(1, "One"))
                .track(TrackTags.of(2, "Second")).build();
        assertEquals(List.of(TrackTags.of(2, "Second"), TrackTags.of(1, "One")), tags.tracks());
        assertEquals(Optional.of(TrackTags.of(1, "One")), tags.track(1));
        assertTrue(tags.track(3).isEmpty());
        assertFalse(tags.isEmpty(), "track tags count");
        assertNotEquals(MediaTags.empty(), tags);
        assertEquals(tags, tags.toBuilder().build());
        assertEquals(List.of(TrackTags.of(1, "One")), tags.toBuilder().removeTrack(2).build().tracks());
        assertTrue(tags.toBuilder().clear().build().tracks().isEmpty());
        assertEquals(tags.tracks(), tags.toBuilder().clearEntries().build().tracks());
        assertEquals(List.of(TrackTags.of(5)), tags.toBuilder().tracks(List.of(TrackTags.of(5, "x"), TrackTags.of(5)))
                .build().tracks());
        assertTrue(tags.toString().contains("Second"));
    }
}
