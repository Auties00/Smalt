package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes every tag of the extended vocabulary (sort names, conductor, lyricist, tempo, ISRC, ReplayGain, MusicBrainz
 * identifiers, rating, description) through every tag format and checks the native names each format stores them
 * under, the values ffprobe and exiftool read back, the agreement of {@code Media.probe}, and that the media
 * packets do not change.
 */
@DisplayName("Tag vocabulary")
class TagVocabularyTest {
    /** The value written for each tag of the extended vocabulary. */
    static final Map<StandardTag, String> VALUES = values();

    /** The tag families: how a container stores the vocabulary. */
    enum Family {
        ID3, VORBIS, MP4, MATROSKA
    }

    /** A container under test. */
    record Case(String name, Path file, Family family) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Map<StandardTag, String> values() {
        var values = new LinkedHashMap<StandardTag, String>();
        values.put(StandardTag.CONDUCTOR, "Herbert Conductor");
        values.put(StandardTag.LYRICIST, "Lyric Writer");
        values.put(StandardTag.DESCRIPTION, "A short description");
        values.put(StandardTag.BPM, "128");
        values.put(StandardTag.ISRC, "USRC17607839");
        values.put(StandardTag.TITLE_SORT, "Vocabulary, The");
        values.put(StandardTag.ARTIST_SORT, "Artist, The");
        values.put(StandardTag.ALBUM_SORT, "Album, The");
        values.put(StandardTag.ALBUM_ARTIST_SORT, "Album Artist, The");
        values.put(StandardTag.COMPOSER_SORT, "Composer, The");
        values.put(StandardTag.REPLAYGAIN_TRACK_GAIN, "-6.48 dB");
        values.put(StandardTag.REPLAYGAIN_TRACK_PEAK, "0.988553");
        values.put(StandardTag.REPLAYGAIN_ALBUM_GAIN, "-7.03 dB");
        values.put(StandardTag.REPLAYGAIN_ALBUM_PEAK, "0.999969");
        values.put(StandardTag.MUSICBRAINZ_RECORDING_ID, "8f3471b5-7e6a-48da-86a9-c1c07a0f47ae");
        values.put(StandardTag.MUSICBRAINZ_RELEASE_TRACK_ID, "6f6e9b3c-5c39-4f45-9a37-1b6d8f6c0f11");
        values.put(StandardTag.MUSICBRAINZ_ALBUM_ID, "a1c35a51-d102-4ce7-aca7-a2a1d9c0a2e6");
        values.put(StandardTag.MUSICBRAINZ_ARTIST_ID, "83d91898-7763-47d7-b03b-b92132375c47");
        values.put(StandardTag.MUSICBRAINZ_ALBUM_ARTIST_ID, "89ad4ac3-39f7-470e-963a-56509c546377");
        values.put(StandardTag.MUSICBRAINZ_RELEASE_GROUP_ID, "1dc4c347-a1db-32aa-b14f-bc9cc507b843");
        values.put(StandardTag.MUSICBRAINZ_WORK_ID, "e8a7c4a4-4c83-3a8f-a0b5-31dfa9ef56ad");
        values.put(StandardTag.MUSICBRAINZ_DISC_ID, "lwHl8fGzJyLXQR33ug60E8jhf4k-");
        values.put(StandardTag.RATING, "4");
        return values;
    }

    static MediaTags sample() {
        var builder = MediaTags.builder().set(StandardTag.TITLE, "Vocabulary").set(StandardTag.ARTIST, "Artist");
        VALUES.forEach((tag, value) -> builder.set(tag, value));
        return builder.build();
    }

    static Stream<Arguments> containers() {
        var cases = new ArrayList<Case>();
        cases.add(new Case("mp3 id3v2.4", TagFixtures.audio("mp3", List.of("-c:a", "libmp3lame", "-b:a", "64k")),
                Family.ID3));
        cases.add(new Case("mp3 id3v2.3", TagFixtures.audio("mp3", List.of("-c:a", "libmp3lame", "-b:a", "64k",
                "-id3v2_version", "3")), Family.ID3));
        cases.add(new Case("adts", TagFixtures.audio("aac", List.of("-c:a", "aac", "-f", "adts")), Family.ID3));
        cases.add(new Case("ac3", TagFixtures.audio("ac3", List.of("-c:a", "ac3")), Family.ID3));
        cases.add(new Case("eac3", TagFixtures.audio("eac3", List.of("-c:a", "eac3")), Family.ID3));
        cases.add(new Case("wav", TagFixtures.audio("wav", List.of("-c:a", "pcm_s16le")), Family.ID3));
        cases.add(new Case("aiff", TagFixtures.audio("aiff", List.of("-c:a", "pcm_s16be")), Family.ID3));
        cases.add(new Case("flac", TagFixtures.audio("flac", List.of("-c:a", "flac")), Family.VORBIS));
        cases.add(new Case("ogg vorbis", TagFixtures.audio("ogg", List.of("-c:a", "libvorbis")), Family.VORBIS));
        cases.add(new Case("ogg opus", TagFixtures.audio("opus", List.of("-c:a", "libopus")), Family.VORBIS));
        cases.add(new Case("m4a", TagFixtures.audio("m4a", List.of("-c:a", "aac", "-b:a", "64k")), Family.MP4));
        cases.add(new Case("mka", TagFixtures.audio("mka", List.of("-c:a", "libopus")), Family.MATROSKA));
        cases.add(new Case("mkv", TagFixtures.video("mkv", List.of("-c:v", "libx264", "-preset", "ultrafast", "-c:a",
                "aac")), Family.MATROSKA));
        return cases.stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("containers")
    @DisplayName("writes the vocabulary, reads it back, agrees with Media.probe and keeps the packets")
    void roundTrip(Case container, @TempDir Path directory) {
        var file = TagFixtures.copy(container.file(), directory);
        var before = TagFixtures.packetHash(file);
        TagEditor.write(file, sample());
        assertEquals(before, TagFixtures.packetHash(file), "packets changed");
        var read = TagEditor.read(file);
        var probed = Media.probe(MediaSource.of(Fixtures.read(file))).tags();
        for (var entry : VALUES.entrySet()) {
            assertEquals(entry.getValue(), read.get(entry.getKey()).orElse(null), entry.getKey() + " in " + read);
            assertEquals(entry.getValue(), probed.get(entry.getKey().key()), entry.getKey() + " in " + probed);
        }
        TagEditor.write(file, read);
        assertEquals(TagWriteResult.UNCHANGED, TagEditor.write(file, TagEditor.read(file)));
        Oracle.assertDecodesCleanly(file);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("containers")
    @DisplayName("stores every tag under the native name of its format")
    void nativeNames(Case container, @TempDir Path directory) throws Exception {
        var file = TagFixtures.copy(container.file(), directory);
        TagEditor.write(file, sample());
        var bytes = Files.readAllBytes(file);
        var names = switch (container.family()) {
            case ID3 -> List.of("TPE3", "TEXT", "TBPM", "TSRC", "TSOT", "TSOP", "TSOA", "TSO2", "TSOC", "UFID",
                    "http://musicbrainz.org", "POPM", "REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_ALBUM_PEAK",
                    "MusicBrainz Release Track Id", "MusicBrainz Album Id", "MusicBrainz Artist Id",
                    "MusicBrainz Album Artist Id", "MusicBrainz Release Group Id", "MusicBrainz Work Id",
                    "MusicBrainz Disc Id", "DESCRIPTION");
            case VORBIS -> List.of("CONDUCTOR=", "LYRICIST=", "DESCRIPTION=", "BPM=", "ISRC=", "TITLESORT=",
                    "ARTISTSORT=", "ALBUMSORT=", "ALBUMARTISTSORT=", "COMPOSERSORT=", "REPLAYGAIN_TRACK_GAIN=",
                    "REPLAYGAIN_ALBUM_PEAK=", "MUSICBRAINZ_TRACKID=", "MUSICBRAINZ_RELEASETRACKID=",
                    "MUSICBRAINZ_ALBUMID=", "MUSICBRAINZ_ARTISTID=", "MUSICBRAINZ_ALBUMARTISTID=",
                    "MUSICBRAINZ_RELEASEGROUPID=", "MUSICBRAINZ_WORKID=", "MUSICBRAINZ_DISCID=", "RATING=");
            case MP4 -> List.of("tmpo", "sonm", "soar", "soal", "soaa", "soco", "desc", "CONDUCTOR", "LYRICIST", "ISRC",
                    "REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_ALBUM_PEAK", "MusicBrainz Track Id",
                    "MusicBrainz Release Track Id", "MusicBrainz Album Id", "MusicBrainz Disc Id", "RATING");
            case MATROSKA -> List.of("SORT_WITH", "REPLAYGAIN_GAIN", "REPLAYGAIN_PEAK", "CONDUCTOR", "LYRICIST", "BPM",
                    "ISRC", "MUSICBRAINZ_TRACKID", "MUSICBRAINZ_ALBUMID", "RATING", "DESCRIPTION", "TRACK");
        };
        var text = new String(bytes, StandardCharsets.ISO_8859_1);
        for (var name : names) {
            assertTrue(text.contains(name), name + " missing from " + container);
        }
        if (container.family() == Family.ID3) {
            for (var legacy : List.of("TXXX:bpm", "conductor\u0000", "title_sort")) {
                assertFalse(text.contains(legacy), legacy);
            }
        }
        if (container.family() == Family.MATROSKA) {
            assertFalse(text.contains("REPLAYGAIN_TRACK_GAIN"), "the track gain of a Matroska file is a level-30 tag");
            assertFalse(text.contains("TITLESORT"), "the sort names of a Matroska file are nested SORT_WITH tags");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("containers")
    @DisplayName("writes values ffprobe reads back")
    void ffprobeReads(Case container, @TempDir Path directory) {
        var file = TagFixtures.copy(container.file(), directory);
        TagEditor.write(file, sample());
        var probe = TagFixtures.ffprobe(file);
        var values = new ArrayList<String>();
        for (var entry : probe.entrySet()) {
            if (entry.getKey().contains(".tags.")) {
                values.add(entry.getValue());
            }
        }
        for (var entry : VALUES.entrySet()) {
            // ffmpeg reads neither the UFID nor the POPM frame of ID3v2, nor the tmpo atom of MP4
            var unread = container.family() == Family.ID3 && (entry.getKey() == StandardTag.MUSICBRAINZ_RECORDING_ID
                    || entry.getKey() == StandardTag.RATING) || container.family() == Family.MP4
                    && entry.getKey() == StandardTag.BPM;
            if (!unread) {
                assertTrue(values.contains(entry.getValue()), entry.getKey() + " missing from " + probe);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"4", "3"})
    @DisplayName("writes ID3v2 frames exiftool names as the tags they are")
    void exiftoolReadsId3(String version, @TempDir Path directory) {
        Oracle.assume(Oracle.Tool.EXIFTOOL);
        var file = TagFixtures.copy(TagFixtures.audio("mp3", List.of("-c:a", "libmp3lame", "-b:a", "64k",
                "-id3v2_version", version)), directory);
        TagEditor.write(file, sample(), TagWriteOptions.builder().id3Version(version.equals("3")
                ? TagWriteOptions.Id3Version.V2_3 : TagWriteOptions.Id3Version.V2_4).build());
        var json = new String(Oracle.run(Oracle.Tool.EXIFTOOL, Duration.ofMinutes(1), List.of("-j", "-n", "-ID3:all",
                file.toString())), StandardCharsets.UTF_8);
        var expected = new LinkedHashMap<String, String>();
        expected.put("BeatsPerMinute", "128");
        expected.put("ISRC", "\"USRC17607839\"");
        expected.put("TitleSortOrder", "\"Vocabulary, The\"");
        expected.put("PerformerSortOrder", "\"Artist, The\"");
        expected.put("AlbumSortOrder", "\"Album, The\"");
        expected.put("AlbumArtistSortOrder", "\"Album Artist, The\"");
        expected.put("ComposerSortOrder", "\"Composer, The\"");
        expected.put("Conductor", "\"Herbert Conductor\"");
        expected.put("Lyricist", "\"Lyric Writer\"");
        expected.put("ReplaygainTrackGain", "\"-6.48 dB\"");
        expected.put("MusicBrainzAlbumId", "\"a1c35a51-d102-4ce7-aca7-a2a1d9c0a2e6\"");
        expected.put("Description", "\"A short description\"");
        for (var entry : expected.entrySet()) {
            assertTrue(json.contains("\"" + entry.getKey() + "\": " + entry.getValue()), entry + " in " + json);
        }
        assertTrue(json.matches("(?s).*\"Popularimeter\": \"[^\"]*\\b204\\b[^\"]*\".*"), json);
    }

    @ParameterizedTest(name = "rating byte {0}")
    @CsvSource({"0,0", "1,0.02", "51,1", "64,1.25", "128,2.51", "196,3.84", "204,4", "254,4.98", "255,5"})
    @DisplayName("reads the POPM byte as a rating from 0 to 5 and writes the same byte back")
    void popularimeter(int rating, String text, @TempDir Path directory) throws Exception {
        var file = directory.resolve("rated.mp3");
        Files.write(file, concat(id3(frame("POPM", concat(latin("Windows Media Player 9 Series\u0000"),
                new byte[]{(byte) rating, 0, 0, 0, 7}))), plainMp3()));
        var read = TagEditor.read(file);
        assertEquals(text, read.get(StandardTag.RATING).orElseThrow());
        TagEditor.write(file, read.toBuilder().set(StandardTag.TITLE, "Rated").build());
        var bytes = Files.readAllBytes(file);
        var expected = concat(latin("Windows Media Player 9 Series\u0000"), new byte[]{(byte) rating, 0, 0, 0, 7});
        assertTrue(indexOf(bytes, expected) > 0, "the e-mail address and the play counter are kept");
        assertEquals(text, TagEditor.read(file).get(StandardTag.RATING).orElseThrow());
    }

    @Test
    @DisplayName("replaces the rating in the first popularimeter, keeps the others, and removes it with the rating")
    void popularimeters(@TempDir Path directory) throws Exception {
        var file = directory.resolve("rated.mp3");
        var first = frame("POPM", concat(latin("first@example.org\u0000"), new byte[]{(byte) 51, 0, 0, 1, 0}));
        var second = frame("POPM", concat(latin("second@example.org\u0000"), new byte[]{(byte) 255}));
        Files.write(file, concat(id3(concat(first, second)), plainMp3()));
        var read = TagEditor.read(file);
        assertEquals(List.of("1"), read.getAll(StandardTag.RATING));
        TagEditor.write(file, read.toBuilder().set(StandardTag.RATING, "4.5").build());
        var bytes = Files.readAllBytes(file);
        assertTrue(indexOf(bytes, concat(latin("first@example.org\u0000"), new byte[]{(byte) 230, 0, 0, 1, 0})) > 0);
        assertTrue(indexOf(bytes, concat(latin("second@example.org\u0000"), new byte[]{(byte) 255})) > 0);
        assertEquals("4.51", TagEditor.read(file).get(StandardTag.RATING).orElseThrow());
        TagEditor.write(file, TagEditor.read(file).toBuilder().remove(StandardTag.RATING).build());
        bytes = Files.readAllBytes(file);
        assertTrue(indexOf(bytes, latin("first@example.org")) < 0, "the rating's popularimeter goes with it");
        assertTrue(indexOf(bytes, latin("second@example.org")) > 0, "another user's popularimeter stays");
    }

    @Test
    @DisplayName("keeps a rating that is not a number from 0 to 5 as user text")
    void textRating(@TempDir Path directory) throws Exception {
        var file = directory.resolve("rated.mp3");
        Files.write(file, plainMp3());
        TagEditor.write(file, MediaTags.builder().set(StandardTag.RATING, "80").build());
        var bytes = Files.readAllBytes(file);
        assertTrue(indexOf(bytes, latin("TXXX")) > 0 && indexOf(bytes, latin("POPM")) < 0);
        assertEquals("80", TagEditor.read(file).get(StandardTag.RATING).orElseThrow());
    }

    @Test
    @DisplayName("reads the MusicBrainz UFID frame and leaves other owners' identifiers opaque")
    void uniqueFileIdentifiers(@TempDir Path directory) throws Exception {
        var file = directory.resolve("ufid.mp3");
        var musicBrainz = frame("UFID", concat(latin("http://musicbrainz.org\u0000"),
                latin("8f3471b5-7e6a-48da-86a9-c1c07a0f47ae")));
        var other = frame("UFID", concat(latin("http://example.org\u0000"), new byte[]{1, 2, 3}));
        Files.write(file, concat(id3(concat(musicBrainz, other)), plainMp3()));
        var read = TagEditor.read(file);
        assertEquals("8f3471b5-7e6a-48da-86a9-c1c07a0f47ae", read.get(StandardTag.MUSICBRAINZ_RECORDING_ID)
                .orElseThrow());
        assertEquals("8f3471b5-7e6a-48da-86a9-c1c07a0f47ae", Media.probe(MediaSource.of(Files.readAllBytes(file)))
                .tags().get("musicbrainz_recording_id"));
        TagEditor.write(file, read.toBuilder().set(StandardTag.MUSICBRAINZ_RECORDING_ID,
                "00000000-0000-4000-8000-000000000000").build());
        var bytes = Files.readAllBytes(file);
        assertTrue(indexOf(bytes, concat(latin("http://example.org\u0000"), new byte[]{1, 2, 3})) > 0);
        assertTrue(indexOf(bytes, latin("8f3471b5")) < 0);
        assertEquals("00000000-0000-4000-8000-000000000000", TagEditor.read(file)
                .get(StandardTag.MUSICBRAINZ_RECORDING_ID).orElseThrow());
    }

    @Test
    @DisplayName("reads the Picard and foobar2000 spellings of the vocabulary")
    void foreignSpellings(@TempDir Path directory) throws Exception {
        var file = directory.resolve("spellings.mp3");
        Files.write(file, concat(id3(concat(userText("replaygain_track_gain", "-1.00 dB"),
                userText("MUSICBRAINZ ALBUM ID", "x"), userText("MusicBrainz Album Artist Id", "y"))), plainMp3()));
        var read = TagEditor.read(file);
        assertEquals("-1.00 dB", read.get(StandardTag.REPLAYGAIN_TRACK_GAIN).orElseThrow());
        assertEquals("y", read.get(StandardTag.MUSICBRAINZ_ALBUM_ARTIST_ID).orElseThrow());
        assertEquals("x", read.get("MUSICBRAINZ ALBUM ID").orElseThrow(), "an unknown spelling stays a free key");
    }

    @Test
    @DisplayName("reads a Matroska ReplayGain value by the level of its tag and keeps the other tags of that level")
    void matroskaLevels(@TempDir Path directory) {
        var file = TagFixtures.copy(TagFixtures.audio("mka", List.of("-c:a", "libopus")), directory);
        TagEditor.write(file, MediaTags.builder().set(StandardTag.TITLE, "Levels")
                .set(StandardTag.REPLAYGAIN_TRACK_GAIN, "-1 dB").set(StandardTag.REPLAYGAIN_ALBUM_GAIN, "-2 dB")
                .set(StandardTag.TITLE_SORT, "Levels, The").build());
        var read = TagEditor.read(file);
        assertEquals("-1 dB", read.get(StandardTag.REPLAYGAIN_TRACK_GAIN).orElseThrow());
        assertEquals("-2 dB", read.get(StandardTag.REPLAYGAIN_ALBUM_GAIN).orElseThrow());
        assertEquals("Levels, The", read.get(StandardTag.TITLE_SORT).orElseThrow());
        var probed = Media.probe(MediaSource.of(Fixtures.read(file))).tags();
        assertEquals("-1 dB", probed.get("replaygain_track_gain"));
        assertEquals("-2 dB", probed.get("replaygain_album_gain"));
        assertEquals("Levels, The", probed.get("title_sort"));
        TagEditor.write(file, read.toBuilder().remove(StandardTag.TITLE_SORT).set(StandardTag.REPLAYGAIN_TRACK_GAIN,
                "-3 dB").build());
        var again = TagEditor.read(file);
        assertEquals("-3 dB", again.get(StandardTag.REPLAYGAIN_TRACK_GAIN).orElseThrow());
        assertTrue(again.get(StandardTag.TITLE_SORT).isEmpty());
        assertEquals("Levels", again.get(StandardTag.TITLE).orElseThrow());
    }

    @Test
    @DisplayName("nests a sort name in its tag even when the tag itself has no value")
    void matroskaSortWithoutParent(@TempDir Path directory) throws Exception {
        var file = TagFixtures.copy(TagFixtures.audio("mka", List.of("-c:a", "libopus")), directory);
        TagEditor.write(file, MediaTags.builder().set(StandardTag.ARTIST_SORT, "Sorted").build());
        assertEquals("Sorted", TagEditor.read(file).get(StandardTag.ARTIST_SORT).orElseThrow());
        assertTrue(TagEditor.read(file).get(StandardTag.ARTIST).isEmpty());
        var text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("ARTIST") && text.contains("SORT_WITH"));
    }

    @Test
    @DisplayName("keeps the RIFF INFO source apart from the ISRC")
    void riffSource(@TempDir Path directory) throws Exception {
        var file = TagFixtures.copy(TagFixtures.audio("wav", List.of("-c:a", "pcm_s16le", "-metadata", "ISRC=Studio")),
                directory);
        var read = TagEditor.read(file);
        assertTrue(read.get(StandardTag.ISRC).isEmpty(), read.toString());
        assertEquals("Studio", read.get("INFO:ISRC").orElseThrow());
        assertNull(Media.probe(MediaSource.of(Files.readAllBytes(file))).tags().get("isrc"));
        TagEditor.write(file, read.toBuilder().set(StandardTag.ISRC, "USRC17607839").build());
        var again = TagEditor.read(file);
        assertEquals("Studio", again.get("INFO:ISRC").orElseThrow());
        assertEquals("USRC17607839", again.get(StandardTag.ISRC).orElseThrow());
        assertTrue(indexOf(Files.readAllBytes(file), latin("ISRC\u0007\u0000\u0000\u0000Studio\u0000")) > 0,
                "the source stays an INFO ISRC entry");
    }

    static byte[] plainMp3() {
        return Fixtures.read(Fixtures.ffmpeg("mp3", List.of("-f", "lavfi", "-i", "sine=duration=0.3"),
                List.of("-c:a", "libmp3lame", "-write_xing", "0", "-id3v2_version", "0")));
    }

    static byte[] latin(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] frame(String id, byte[] content) {
        var header = new byte[10];
        System.arraycopy(latin(id), 0, header, 0, 4);
        header[4] = (byte) (content.length >>> 21 & 0x7F);
        header[5] = (byte) (content.length >>> 14 & 0x7F);
        header[6] = (byte) (content.length >>> 7 & 0x7F);
        header[7] = (byte) (content.length & 0x7F);
        return concat(header, content);
    }

    static byte[] userText(String description, String value) {
        return frame("TXXX", concat(new byte[]{3}, (description + "\u0000" + value).getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] id3(byte[] frames) {
        var header = new byte[]{'I', 'D', '3', 4, 0, 0, (byte) (frames.length >>> 21 & 0x7F),
                (byte) (frames.length >>> 14 & 0x7F), (byte) (frames.length >>> 7 & 0x7F), (byte) (frames.length & 0x7F)};
        return concat(header, frames);
    }

    static byte[] concat(byte[]... parts) {
        var out = new java.io.ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (var start = 0; start + pattern.length <= data.length; start++) {
            for (var index = 0; index < pattern.length; index++) {
                if (data[start + index] != pattern[index]) {
                    continue outer;
                }
            }
            return start;
        }
        return -1;
    }
}
