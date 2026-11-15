package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.asf.AsfDemuxer;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads and writes the tags of ASF files (the Content Description, Extended Content Description, Metadata Library and
 * Marker Objects) with ffprobe and exiftool as the independent readers and the packets of Smalt's demuxer and
 * ffmpeg's packet hashes proving the media unchanged: a write that fits the old header pads it and is in place; a
 * larger one moves the data object and updates the file size the File Properties Object records.
 */
@DisplayName("ASF tags")
class AsfTagsTest {
    static Path fixture() {
        return Fixtures.ffmpeg("wmv", List.of("-f", "lavfi", "-i", TagFixtures.VIDEO, "-f", "lavfi", "-i",
                TagFixtures.AUDIO), List.of("-map", "0:v", "-map", "1:a", "-shortest", "-c:v", "wmv2", "-c:a", "wmav2",
                "-metadata", "title=Fixture Title", "-metadata", "artist=Fixture Artist", "-metadata", "genre=Rock",
                "-metadata", "album=Fixture Album", "-metadata", "comment=Fixture comment", "-metadata", "track=4"));
    }

    static List<String> packets(Path file) {
        try (var source = MediaSource.of(file); var demuxer = new AsfDemuxer(source)) {
            var lines = new ArrayList<String>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                lines.add(packet.trackId() + " " + packet.pts() + " " + Arrays.hashCode(packet.data()));
            }
            return lines;
        }
    }

    static Map<String, String> exiftool(Path file) {
        Oracle.assume(Oracle.Tool.EXIFTOOL);
        var json = new String(Oracle.run(Oracle.Tool.EXIFTOOL, Duration.ofMinutes(1), List.of("-j", "-s",
                "-charset", "UTF8", "-ASF:all", file.toString())), StandardCharsets.UTF_8);
        var result = new java.util.LinkedHashMap<String, String>();
        var matcher = Pattern.compile("\"([A-Za-z0-9]+)\": (\"((?:[^\"\\\\]|\\\\.)*)\"|[-0-9.]+)").matcher(json);
        while (matcher.find()) {
            result.putIfAbsent(matcher.group(1), matcher.group(3) != null ? matcher.group(3) : matcher.group(2));
        }
        return result;
    }

    @Test
    @DisplayName("reads the tags ffmpeg wrote as ffprobe does")
    void readsFixture() {
        var file = fixture();
        var tags = TagEditor.read(file);
        var probe = TagFixtures.ffprobeTags(file);
        for (var tag : List.of(StandardTag.TITLE, StandardTag.ARTIST, StandardTag.GENRE, StandardTag.ALBUM,
                StandardTag.COMMENT, StandardTag.TRACK)) {
            assertEquals(probe.get(tag.key()), tags.get(tag).orElseThrow(), tag.key());
        }
        assertTrue(TagEditor.isSupported("asf"));
    }

    @Test
    @DisplayName("writes every tag, a picture and chapters, moving the media once and editing in place afterwards")
    void writes(@TempDir Path directory) throws IOException {
        var file = TagFixtures.copy(fixture(), directory);
        var before = packets(file);
        var hash = TagFixtures.packetHash(file);
        var sample = TagFixtures.sample();
        assertEquals(TagWriteResult.REWRITTEN, TagEditor.write(file, sample));
        assertEquals(before, packets(file), "the media objects are unchanged");
        assertEquals(hash, TagFixtures.packetHash(file), "ffmpeg reads the same packets");
        var bytes = Files.readAllBytes(file);
        assertEquals(bytes.length, fileSize(bytes), "the File Properties Object records the new size");
        var read = TagEditor.read(file);
        for (var entry : sample.entries()) {
            assertTrue(read.getAll(entry.key()).contains(entry.value()), entry + " in " + read.entries());
        }
        assertEquals(sample.pictures(), read.pictures());
        var starts = read.chapters().stream().map(Chapter::startMicros).toList();
        assertEquals(sample.chapters().stream().map(Chapter::startMicros).toList(), starts);
        assertEquals(List.of("One", "Two", "Three"), read.chapters().stream().map(c -> c.title().orElseThrow())
                .toList());
        var probe = TagFixtures.ffprobeTags(file);
        assertEquals("Edited Title è中", probe.get("title"));
        assertEquals("Edited Album", probe.get("album"));
        assertEquals("Album Artist", probe.get("album_artist"));
        assertEquals("Jazz", probe.get("genre"));
        assertEquals(List.of(0L, 700_000L, 1_500_000L), TagFixtures.ffprobeChapters(file).stream()
                .map(Chapter::startMicros).toList());
        assertEquals(1, TagFixtures.ffprobeAttachedPictures(file));
        var exif = exiftool(file);
        assertEquals("Edited Title è中", exif.get("Title"));
        // the first artist is the Content Description author, the second an Author descriptor of the Extended
        // Content Description Object, which exiftool reports last and so in place of the first
        assertEquals("Second Artist", exif.get("Author"));
        assertEquals("Edited Album", exif.get("AlbumTitle"));
        assertEquals("Composer", exif.get("Composer"));

        var smaller = MediaTags.builder().set(StandardTag.TITLE, "Short").build();
        assertEquals(TagWriteResult.IN_PLACE, TagEditor.write(file, smaller));
        assertEquals(bytes.length, Files.size(file), "the header keeps its size, padded");
        assertEquals(before, packets(file));
        var again = TagEditor.read(file);
        assertEquals(List.of(new MediaTags.Entry("title", "Short")), again.entries());
        assertTrue(again.pictures().isEmpty() && again.chapters().isEmpty());
        assertEquals("Short", exiftool(file).get("Title"));
    }

    @Test
    @DisplayName("stores a picture too large for a descriptor in the Metadata Library Object")
    void largePicture(@TempDir Path directory) {
        var file = TagFixtures.copy(fixture(), directory);
        var data = new byte[200_000];
        var cover = Fixtures.read(TagFixtures.cover());
        System.arraycopy(cover, 0, data, 0, cover.length);
        var picture = AttachedPicture.of(AttachedPicture.Type.BACK_COVER, "image/png", "large", data);
        TagEditor.write(file, MediaTags.builder().set(StandardTag.TITLE, "Large").addPicture(picture).build());
        assertEquals(List.of(picture), TagEditor.read(file).pictures());
        try (var source = MediaSource.of(file); var demuxer = new AsfDemuxer(source)) {
            assertEquals(List.of(picture), demuxer.attachedPictures());
        }
    }

    @Test
    @DisplayName("keeps the records of single streams, and the byte arrays only while unknown items are preserved")
    void preserves(@TempDir Path directory) {
        var file = TagFixtures.copy(fixture(), directory);
        TagEditor.write(file, MediaTags.builder().set(StandardTag.TITLE, "T").build());
        try (var source = MediaSource.of(file); var demuxer = new AsfDemuxer(source)) {
            var video = demuxer.trackMetadata(1);
            assertTrue(video.containsKey("AspectRatioX") || demuxer.trackMetadata(2).containsKey("AspectRatioX"),
                    "the per-stream aspect ratio records survive");
        }
        assertThrows(MediaException.Unsupported.class, () -> TagEditor.write(file, MediaTags.builder()
                .addChapter(new Chapter(0, 100, java.util.Optional.of("Parent"), List.of(),
                        List.of(Chapter.of(0, 50, "Child")))).build()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"asf-samples-wma_with_WmPicture.wma", "asf-samples-wma_with_metadata_library_object_tag.wma",
            "asf-samples-wma_with_ID3_APIC.wma"})
    @DisplayName("reads the tags of Windows Media files as exiftool does and their pictures as ffmpeg extracts them")
    void windowsMedia(String name, @TempDir Path directory) throws IOException {
        var file = Conformance.fetchListed(name);
        var tags = TagEditor.read(file);
        var exif = exiftool(file);
        var names = Map.of("Title", StandardTag.TITLE, "Author", StandardTag.ARTIST, "AlbumTitle", StandardTag.ALBUM,
                "AlbumArtist", StandardTag.ALBUM_ARTIST, "Year", StandardTag.DATE, "Genre", StandardTag.GENRE,
                "Publisher", StandardTag.PUBLISHER, "Composer", StandardTag.COMPOSER);
        var compared = 0;
        for (var entry : names.entrySet()) {
            var expected = exif.get(entry.getKey());
            if (expected != null && !expected.isEmpty()) {
                assertEquals(expected, tags.get(entry.getValue()).orElse(null), entry.getKey());
                compared++;
            }
        }
        assertTrue(compared >= 4, "exiftool reported " + exif);
        Oracle.assume(Oracle.Tool.FFMPEG);
        var pictures = tags.pictures();
        assertEquals(TagFixtures.ffprobeAttachedPictures(file), pictures.size());
        for (var index = 0; index < pictures.size(); index++) {
            var out = directory.resolve("picture" + index + ".jpg");
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-i", file.toString(),
                    "-map", "0:v:" + index, "-c", "copy", "-frames:v", "1", "-f", "image2", out.toString()));
            assertArrayEquals(Files.readAllBytes(out), pictures.get(index).data(), "picture " + index);
        }
    }

    private static long fileSize(byte[] file) {
        var position = 30;
        while (position + 24 <= file.length) {
            var size = ByteArrays.i64le(file, position + 16);
            if ((file[position] & 0xFF) == 0xA1 && (file[position + 1] & 0xFF) == 0xDC) {
                return ByteArrays.i64le(file, position + 40);
            }
            position += (int) size;
        }
        throw new AssertionError("no file properties object");
    }
}
