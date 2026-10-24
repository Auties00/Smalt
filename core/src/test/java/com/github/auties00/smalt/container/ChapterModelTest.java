package com.github.auties00.smalt.container;

import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TrackTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Chapter editions, localized titles and attachments")
class ChapterModelTest {
    @Test
    @DisplayName("keeps the title equal to the first localized title")
    void titles() {
        var chapter = Chapter.of(0, 10, "a").withTitles(List.of(new Chapter.Title("b", "fre"), new Chapter.Title("c", "ger")));
        assertEquals(Optional.of("b"), chapter.title());
        assertEquals(Optional.of("b"), chapter.withTitles(List.of()).title());
        assertThrows(IllegalArgumentException.class, () -> new Chapter(0, 10, Optional.of("x"),
                List.of(new Chapter.Title("y", "eng")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Chapter(0, 10, Optional.empty(),
                List.of(new Chapter.Title("y", "eng")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Chapter.Title("", "eng"));
        assertThrows(IllegalArgumentException.class, () -> new Chapter.Title("x", " "));
        assertThrows(NullPointerException.class, () -> Chapter.of(0, 1, null).withChildren(java.util.Arrays.asList(
                (Chapter) null)));
        assertEquals(new Chapter(5, 6, Optional.empty()), Chapter.of(5, 6, null));
    }

    @Test
    @DisplayName("takes the chapters of the default edition, the first when none is flagged")
    void defaultEdition() {
        var first = ChapterEdition.of(List.of(Chapter.of(10, 20, "late"), Chapter.of(0, 10, "early")));
        var second = new ChapterEdition(List.of(Chapter.of(0, 5, "flagged")), true, false, List.of());
        var ordered = new ChapterEdition(List.of(Chapter.of(10, 20, "second"), Chapter.of(0, 10, "first")), false,
                true, List.of());
        assertEquals(-1, ChapterEdition.defaultIndex(List.of()));
        assertEquals(0, ChapterEdition.defaultIndex(List.of(first, ordered)));
        assertEquals(1, ChapterEdition.defaultIndex(List.of(first, second)));
        var tags = MediaTags.builder().editions(List.of(first, second, ordered)).build();
        assertEquals(List.of(Chapter.of(0, 5, "flagged")), tags.chapters());
        // an edition that is not ordered is sorted, an ordered one keeps its order
        assertEquals("early", tags.editions().getFirst().chapters().getFirst().title().orElseThrow());
        assertEquals("second", tags.editions().get(2).chapters().getFirst().title().orElseThrow());
        var unordered = MediaTags.builder().editions(List.of(ordered.withChapters(ordered.chapters()), first)).build();
        assertEquals(List.of(Chapter.of(0, 10, "first"), Chapter.of(10, 20, "second")), unordered.chapters());
    }

    @Test
    @DisplayName("adds chapters to the default edition and replaces every edition with chapters(...)")
    void builder() {
        var builder = MediaTags.builder().addChapter(Chapter.of(0, 1, "a"));
        assertEquals(1, builder.build().editions().size());
        builder.editions(List.of(ChapterEdition.of(List.of(Chapter.of(0, 1, "x"))),
                new ChapterEdition(List.of(Chapter.of(0, 1, "y")), true, false, List.of())));
        builder.addChapter(Chapter.of(1, 2, "z"));
        var tags = builder.build();
        assertEquals(List.of(Chapter.of(0, 1, "y"), Chapter.of(1, 2, "z")), tags.chapters());
        assertEquals(1, tags.editions().getFirst().chapters().size());
        var replaced = tags.toBuilder().chapters(List.of(Chapter.of(3, 4, "only"))).build();
        assertEquals(1, replaced.editions().size());
        assertTrue(tags.toBuilder().chapters(List.of()).build().editions().isEmpty());
        assertNotEquals(tags, replaced);
        assertEquals(tags, tags.toBuilder().build());
    }

    @Test
    @DisplayName("holds attachments by value, copied in and out")
    void attachments() {
        var data = new byte[]{1, 2, 3};
        var attachment = Attachment.of("font.ttf", "font/ttf", "", data);
        data[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, attachment.data());
        attachment.data()[1] = 9;
        assertEquals(Attachment.of("font.ttf", "font/ttf", "", new byte[]{1, 2, 3}), attachment);
        assertEquals(3, attachment.size());
        assertThrows(IllegalArgumentException.class, () -> Attachment.of(" ", "font/ttf", "", data));
        assertThrows(IllegalArgumentException.class, () -> Attachment.of("a", "", "", data));
        var tags = MediaTags.builder().addAttachment(attachment).build();
        assertEquals(List.of(attachment), tags.attachments());
        assertTrue(tags.toBuilder().attachments(List.of()).build().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new TrackTags(1, tags, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new TrackTags(1, MediaTags.builder()
                .editions(List.of(ChapterEdition.of(List.of(Chapter.of(0, 1, null))))).build(), Set.of()));
    }
}
