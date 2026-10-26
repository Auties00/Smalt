package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.track.TrackDisposition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the shared tag vocabulary and the tag container: key normalization across formats,
 * multi-valued tags, track and disc numbers, and entry validation.
 */
@DisplayName("MediaTags")
class MediaTagsTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"TIT2,title", "TITLE,title", "INAM,title", "title,title", "\u00A9nam,title", "TPE2,album_artist",
            "ALBUMARTIST,album_artist", "ALBUM ARTIST,album_artist", "TRACKNUMBER,track", "trkn,track", "PART_NUMBER,track",
            "TYER,date", "TDRC,date", "DATE_RELEASED,date", "DESCRIPTION,description", "desc,description",
            "COMM,comment", "ANNO,comment", "TCMP,compilation", "cpil,compilation",
            "REPLAYGAIN_TRACK_GAIN,replaygain_track_gain", "replaygain_album_peak,replaygain_album_peak",
            "REPLAYGAIN_GAIN,REPLAYGAIN_GAIN", "TBPM,bpm", "tmpo,bpm", "TSRC,isrc", "TSOT,title_sort",
            "TITLESORT,title_sort", "sonm,title_sort", "TITLE/SORT_WITH,title_sort", "title-sort,title_sort",
            "soaa,album_artist_sort", "TSO2,album_artist_sort", "TPE3,conductor", "TEXT,lyricist",
            "UFID,musicbrainz_recording_id", "MusicBrainz Track Id,musicbrainz_recording_id",
            "MUSICBRAINZ_TRACKID,musicbrainz_recording_id", "MusicBrainz Album Id,musicbrainz_album_id",
            "MUSICBRAINZ_RELEASEGROUPID,musicbrainz_release_group_id", "POPM,rating", "RATING,rating"})
    @DisplayName("normalizes the native names of every format")
    void normalize(String name, String key) {
        assertEquals(key, StandardTag.normalize(name));
    }

    @Test
    @DisplayName("names the native slot of every standard tag")
    void nativeNames() {
        assertEquals(Optional.of("TIT2"), StandardTag.TITLE.id3Frame());
        assertEquals("ALBUMARTIST", StandardTag.ALBUM_ARTIST.vorbisField());
        assertEquals(Optional.of("trkn"), StandardTag.TRACK.mp4Atom());
        assertEquals("PART_NUMBER", StandardTag.TRACK.matroskaName());
        assertEquals(Optional.of("INAM"), StandardTag.TITLE.riffInfo());
        for (var tag : StandardTag.values()) {
            assertEquals(Optional.of(tag), StandardTag.fromName(tag.key()));
            assertEquals(Optional.of(tag), StandardTag.fromName(tag.vorbisField()));
            assertEquals(Optional.of(tag), StandardTag.fromName(tag.id3Description()));
            assertEquals(Optional.of(tag), StandardTag.fromName(tag.mp4FreeformName()));
            tag.id3Frame().ifPresent(frame -> assertEquals(Optional.of(tag), StandardTag.fromName(frame)));
            tag.mp4Atom().ifPresent(atom -> assertEquals(Optional.of(tag), StandardTag.fromName(atom)));
            if (!tag.matroskaName().startsWith("REPLAYGAIN_")) {
                assertEquals(Optional.of(tag), StandardTag.fromName(tag.matroskaName()));
            }
        }
        assertTrue(StandardTag.fromName("REPLAYGAIN_GAIN").isEmpty(), "a Matroska name whose meaning depends on its level");
    }

    @Test
    @DisplayName("keeps multiple values in order and replaces them in place")
    void values() {
        var tags = MediaTags.builder().set("ARTIST", "A", "B").add(StandardTag.TITLE, "T").add("artist", "C")
                .set("Mood", "calm").build();
        assertEquals(List.of("A", "B", "C"), tags.getAll(StandardTag.ARTIST));
        assertEquals(Map.of("artist", "A; B; C", "title", "T", "Mood", "calm"), tags.asMap());
        assertEquals("calm", tags.get("MOOD").orElseThrow());
        var replaced = tags.toBuilder().set(StandardTag.ARTIST, "Z").build();
        assertEquals("artist", replaced.entries().getFirst().key());
        assertEquals(List.of("Z"), replaced.getAll("TPE1"));
        assertTrue(replaced.toBuilder().remove("tpe1").build().get(StandardTag.ARTIST).isEmpty());
    }

    @Test
    @DisplayName("parses track and disc numbers with totals")
    void numbers() {
        var tags = MediaTags.builder().set(StandardTag.TRACK, "3/12").set(StandardTag.DISC, "2").build();
        assertEquals(3, tags.trackNumber().orElseThrow());
        assertEquals(12, tags.trackTotal().orElseThrow());
        assertEquals(2, tags.discNumber().orElseThrow());
        assertTrue(tags.discTotal().isEmpty());
        assertTrue(MediaTags.builder().set(StandardTag.TRACK, "x").build().trackNumber().isEmpty());
    }

    @Test
    @DisplayName("sorts chapters and returns the shared empty instance")
    void chapters() {
        var tags = MediaTags.builder().addChapter(Chapter.of(5, 10, "b")).addChapter(Chapter.of(0, 5, "a")).build();
        assertEquals("a", tags.chapters().getFirst().title().orElseThrow());
        assertSame(MediaTags.empty(), MediaTags.builder().build());
        assertTrue(MediaTags.empty().isEmpty());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"", " ", "a=b", "tab\tkey"})
    @DisplayName("rejects keys no format can store")
    void invalidKeys(String key) {
        assertThrows(IllegalArgumentException.class, () -> MediaTags.builder().add(key, "value"));
    }

    @Test
    @DisplayName("rejects values holding a NUL character")
    void invalidValue() {
        assertThrows(IllegalArgumentException.class, () -> MediaTags.builder().add("key", "a\0b"));
    }

    @Test
    @DisplayName("holds an XMP packet that counts in equality and emptiness and is cleared by noXmp")
    void xmp() {
        var packet = XmpPacket.empty().with(XmpPacket.DC, "title", "Packet");
        var tags = MediaTags.builder().xmp(packet).build();
        assertFalse(tags.isEmpty());
        assertEquals(Optional.of(packet), tags.xmp());
        assertEquals(tags, tags.toBuilder().build());
        assertNotEquals(tags, MediaTags.empty());
        assertSame(MediaTags.empty(), tags.toBuilder().noXmp().build());
        assertSame(MediaTags.empty(), tags.toBuilder().clear().build());
        assertTrue(tags.toString().contains("Packet"), tags.toString());
        assertThrows(NullPointerException.class, () -> MediaTags.builder().xmp(null));
        assertThrows(IllegalArgumentException.class, () -> new TrackTags(1, tags, Set.of()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"location", "LOCATION", "recording_location", "\u00A9xyz", "loci",
            "com.apple.quicktime.location.ISO6709", "com.apple.quicktime.location.accuracy.horizontal",
            "COM.APPLE.QUICKTIME.LOCATION.NAME"})
    @DisplayName("recognizes every key that records a place and removes it from the file and its tracks")
    void locationKeys(String key) {
        var tags = MediaTags.builder().set(StandardTag.TITLE, "kept").add(key, "+45.4642+009.1900/")
                .add("com.apple.quicktime.make", "Apple").build();
        assertTrue(tags.hasLocation(), key);
        var stripped = tags.toBuilder().removeLocation().build();
        assertFalse(stripped.hasLocation());
        assertEquals(List.of("title", "com.apple.quicktime.make"), List.copyOf(stripped.keys()));
        var track = new TrackTags(2, MediaTags.builder().set(StandardTag.TITLE, "track").add(key, "x").build(),
                Set.of(TrackDisposition.DEFAULT));
        var withTrack = MediaTags.builder().track(track).build();
        assertTrue(withTrack.hasLocation());
        var strippedTrack = withTrack.toBuilder().removeLocation().build().track(2).orElseThrow();
        assertEquals(List.of("title"), List.copyOf(strippedTrack.tags().keys()));
        assertEquals(Set.of(TrackDisposition.DEFAULT), strippedTrack.disposition());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"locations", "com.apple.quicktime.make", "com.apple.quicktime.locationx", "geo", "xyz"})
    @DisplayName("does not take other keys for a place")
    void otherKeys(String key) {
        var tags = MediaTags.builder().add(key, "value").build();
        assertFalse(tags.hasLocation(), key);
        assertEquals(tags, tags.toBuilder().removeLocation().build());
    }

    @Test
    @DisplayName("removes the XMP properties that record a place and drops a packet left empty")
    void xmpLocation() {
        var packet = XmpPacket.empty().with(XmpPacket.DC, "title", "Kept").with(XmpPacket.EXIF, "GPSLatitude",
                "45,27.852N").with(XmpPacket.PHOTOSHOP, "City", "Milano")
                .with(XmpPacket.IPTC_EXTENSION, "LocationShown", "Duomo");
        var tags = MediaTags.builder().xmp(packet).build();
        assertTrue(tags.hasLocation());
        var stripped = tags.toBuilder().removeLocation().build();
        assertFalse(stripped.hasLocation());
        assertEquals(Optional.of("Kept"), stripped.xmp().orElseThrow().text(XmpPacket.DC, "title"));
        assertEquals(1, stripped.xmp().orElseThrow().properties().size());
        var onlyPlace = MediaTags.builder().xmp(XmpPacket.empty().with(XmpPacket.EXIF, "GPSLongitude", "9,11.4E"))
                .build();
        assertTrue(onlyPlace.toBuilder().removeLocation().build().xmp().isEmpty());
        assertFalse(MediaTags.builder().xmp(XmpPacket.empty().with(XmpPacket.DC, "title", "x")).build().hasLocation());
    }
}
