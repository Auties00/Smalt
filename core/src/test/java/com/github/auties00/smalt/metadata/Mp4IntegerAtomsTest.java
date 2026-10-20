package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the iTunes integer atoms {@link TagEditor} reads and writes as decimal text, against ffmpeg as an
 * independent writer, ffprobe and ExifTool as independent readers, {@code Media.probe}, and the bytes of the
 * {@code data} atoms (type 21, big-endian, the width iTunes uses).
 */
@DisplayName("MP4 integer atoms")
class Mp4IntegerAtomsTest {
    /** The values written and the width in bytes each atom must get. */
    static final Map<String, String> VALUES = new LinkedHashMap<>();

    static final Map<String, Integer> WIDTHS = Map.ofEntries(Map.entry("tmpo", 2), Map.entry("rtng", 1),
            Map.entry("stik", 1), Map.entry("pgap", 1), Map.entry("hdvd", 1), Map.entry("tvsn", 4), Map.entry("tves", 4),
            Map.entry("plID", 8), Map.entry("cnID", 4), Map.entry("atID", 4), Map.entry("geID", 4), Map.entry("sfID", 4),
            Map.entry("cmID", 4), Map.entry("akID", 1), Map.entry("shwm", 1));

    static {
        VALUES.put("tmpo", "128");
        VALUES.put("rtng", "2");
        VALUES.put("stik", "10");
        VALUES.put("pgap", "1");
        VALUES.put("hdvd", "2");
        VALUES.put("tvsn", "5");
        VALUES.put("tves", "12");
        VALUES.put("plID", "1234567890123");
        VALUES.put("cnID", "987654");
        VALUES.put("atID", "123456");
        VALUES.put("geID", "2147483647");
        VALUES.put("sfID", "143441");
        VALUES.put("cmID", "77");
        VALUES.put("akID", "1");
        VALUES.put("shwm", "0");
    }

    static Path untagged() {
        return MuxedTagsTest.untagged("m4a", List.of("-c:a", "aac"));
    }

    static MediaTags integerTags() {
        var builder = MediaTags.builder().set(StandardTag.TITLE, "Integers");
        VALUES.forEach(builder::set);
        return builder.build();
    }

    static byte[] withIntegers() {
        return TagEditor.write(Fixtures.read(untagged()), integerTags(), TagWriteOptions.defaults());
    }

    /** Runs ExifTool and returns the numeric values of the item list. */
    static Map<String, String> exiftool(Path file) {
        Oracle.assume(Oracle.Tool.EXIFTOOL);
        var json = new String(Oracle.run(Oracle.Tool.EXIFTOOL, Duration.ofMinutes(1), List.of("-j", "-n", "-ItemList:all",
                file.toString())), StandardCharsets.UTF_8);
        var result = new HashMap<String, String>();
        var matcher = Pattern.compile("\"(\\w+)\": \"?([^\",\\r\\n]*)\"?").matcher(json);
        while (matcher.find()) {
            result.put(matcher.group(1), matcher.group(2));
        }
        return result;
    }

    static Path save(Path directory, byte[] data) throws java.io.IOException {
        var file = directory.resolve("integers.m4a");
        Files.write(file, data);
        return file;
    }

    /** Rewrites the payload and type of the first data atom of an item, keeping its size. */
    static byte[] patch(byte[] data, String code, int type, byte[] payload) {
        var copy = data.clone();
        var item = Mp4Atoms.all(copy, "moov/udta/meta/ilst/" + code).getFirst();
        var atom = Mp4Atoms.children(copy, item.payload(), item.end()).stream().filter(box -> box.type().equals("data"))
                .findFirst().orElseThrow();
        assertEquals(payload.length, atom.end() - atom.payload() - 8, "the placeholder has the payload's size");
        copy[atom.payload() + 3] = (byte) type;
        System.arraycopy(payload, 0, copy, atom.payload() + 8, payload.length);
        return copy;
    }

    @Test
    @DisplayName("reads the integer atoms ffmpeg writes")
    void readsFfmpeg() {
        var file = Fixtures.ffmpeg("m4a", List.of("-f", "lavfi", "-i", TagFixtures.AUDIO), List.of("-c:a", "aac",
                "-metadata", "media_type=2", "-metadata", "hd_video=1", "-metadata", "gapless_playback=1", "-metadata",
                "season_number=3", "-metadata", "episode_sort=7", "-metadata", "tmpo=120"));
        var data = Fixtures.read(file);
        assertEquals(List.of(21), Mp4Atoms.itemData(data, "tmpo").stream().map(pair -> pair[0]).toList());
        var tags = TagEditor.read(MediaSource.of(data));
        assertEquals("2", tags.get("stik").orElseThrow());
        assertEquals("1", tags.get("hdvd").orElseThrow());
        assertEquals("1", tags.get("pgap").orElseThrow());
        assertEquals("3", tags.get("tvsn").orElseThrow());
        assertEquals("7", tags.get("tves").orElseThrow());
        assertEquals("120", tags.get("tmpo").orElseThrow());
        var probed = Media.probe(MediaSource.of(data)).tags();
        for (var code : List.of("stik", "hdvd", "pgap", "tvsn", "tves", "tmpo")) {
            assertEquals(tags.get(code).orElseThrow(), probed.get(code), code + " in " + probed);
        }
    }

    @Test
    @DisplayName("writes every integer atom as a big-endian type 21 integer of its width and reads it back")
    void writesWidths() {
        var data = withIntegers();
        for (var entry : VALUES.entrySet()) {
            var atoms = Mp4Atoms.itemData(data, entry.getKey());
            assertEquals(1, atoms.size(), entry.getKey());
            assertEquals(21, atoms.getFirst()[0], entry.getKey());
            var payload = (byte[]) atoms.getFirst()[1];
            assertEquals(WIDTHS.get(entry.getKey()), payload.length, entry.getKey());
            var value = 0L;
            for (var b : payload) {
                value = (value << 8) | (b & 0xFF);
            }
            var shift = 64 - 8 * payload.length;
            assertEquals(Long.parseLong(entry.getValue()), value << shift >> shift, entry.getKey());
        }
        var read = TagEditor.read(MediaSource.of(data));
        var probed = Media.probe(MediaSource.of(data)).tags();
        for (var entry : VALUES.entrySet()) {
            assertEquals(entry.getValue(), read.get(entry.getKey()).orElseThrow(), entry.getKey());
            assertEquals(entry.getValue(), probed.get(entry.getKey()), entry.getKey() + " in " + probed);
        }
        assertArrayEquals(data, TagEditor.write(data, read, TagWriteOptions.defaults()), "a rewrite is the identity");
    }

    @Test
    @DisplayName("agrees with ffprobe and ExifTool on the values it writes")
    void oracles(@TempDir Path directory) throws Exception {
        var file = save(directory, withIntegers());
        var probe = TagFixtures.ffprobeTags(file);
        assertEquals("10", probe.get("media_type"), probe.toString());
        assertEquals("2", probe.get("hd_video"), probe.toString());
        assertEquals("1", probe.get("gapless_playback"), probe.toString());
        assertEquals("5", probe.get("season_number"), probe.toString());
        assertEquals("12", probe.get("episode_sort"), probe.toString());
        var exif = exiftool(file);
        assertEquals("128", exif.get("BeatsPerMinute"), exif.toString());
        assertEquals("2", exif.get("Rating"), exif.toString());
        assertEquals("10", exif.get("MediaType"), exif.toString());
        assertEquals("1", exif.get("PlayGap"), exif.toString());
        assertEquals("2", exif.get("HDVideo"), exif.toString());
        assertEquals("5", exif.get("TVSeason"), exif.toString());
        assertEquals("12", exif.get("TVEpisode"), exif.toString());
        assertEquals("1234567890123", exif.get("AlbumID"), exif.toString());
        assertEquals("987654", exif.get("AppleStoreCatalogID"), exif.toString());
        assertEquals("123456", exif.get("ArtistID"), exif.toString());
        assertEquals("2147483647", exif.get("GenreID"), exif.toString());
        assertEquals("143441", exif.get("AppleStoreCountry"), exif.toString());
        assertEquals("77", exif.get("ComposerID"), exif.toString());
        assertEquals("1", exif.get("AppleStoreAccountType"), exif.toString());
        assertEquals("0", exif.get("ShowMovement"), exif.toString());
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({"tmpo,fast", "tmpo,32768", "rtng,128", "stik,-129", "tvsn,2147483648", "plID,1.5", "hdvd,0x1",
            "pgap,maybe"})
    @DisplayName("rejects a value its integer atom cannot hold")
    void rejects(String code, String value) {
        var tags = MediaTags.builder().set(code, value).build();
        var file = Fixtures.read(untagged());
        var exception = assertThrows(MediaException.Unsupported.class,
                () -> TagEditor.write(file, tags, TagWriteOptions.defaults()));
        assertTrue(exception.getMessage().contains(code), exception.getMessage());
        try (var muxer = new Mp4Muxer(MediaSink.memory())) {
            assertThrows(MediaException.Unsupported.class, () -> muxer.setTags(tags));
        }
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({"pgap,true,1", "pgap,NO,0", "shwm,yes,1", "tmpo,' 90 ',90", "tmpo,-32768,-32768", "rtng,127,127"})
    @DisplayName("accepts flag words for flags and trims numbers")
    void accepts(String code, String value, String expected) {
        var data = TagEditor.write(Fixtures.read(untagged()), MediaTags.builder().set(code, value).build(),
                TagWriteOptions.defaults());
        assertEquals(expected, TagEditor.read(MediaSource.of(data)).get(code).orElseThrow());
    }

    @Test
    @DisplayName("keeps the signedness and width of integer atoms it does not know")
    void unknownAtoms() {
        var placeholders = MediaTags.builder().set("xu32", "AAAA").set("xs8a", "A").set("xu64", "AAAAAAAA")
                .set("xs24", "AAA").set("tmpo", "1").build();
        var data = TagEditor.write(Fixtures.read(untagged()), placeholders, TagWriteOptions.defaults());
        data = patch(data, "xu32", 22, new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFE});
        data = patch(data, "xs8a", 21, new byte[]{(byte) 0xFF});
        data = patch(data, "xu64", 22, new byte[]{-1, -1, -1, -1, -1, -1, -1, -1});
        data = patch(data, "xs24", 21, new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFE});
        data = patch(data, "tmpo", 22, new byte[]{(byte) 0xFF, (byte) 0xFF});
        var read = TagEditor.read(MediaSource.of(data));
        assertEquals("4294967294", read.get("xu32").orElseThrow());
        assertEquals("-1", read.get("xs8a").orElseThrow());
        assertEquals("18446744073709551615", read.get("xu64").orElseThrow());
        assertEquals("-2", read.get("xs24").orElseThrow());
        assertEquals("65535", read.get("tmpo").orElseThrow());
        var probed = Media.probe(MediaSource.of(data)).tags();
        for (var code : List.of("xu32", "xs8a", "xu64", "xs24", "tmpo")) {
            assertEquals(read.get(code).orElseThrow(), probed.get(code), code);
        }
        var rewritten = TagEditor.write(data, read.toBuilder().set("xu32", "7").set(StandardTag.TITLE, "t").build(),
                TagWriteOptions.defaults());
        assertArrayEquals(new byte[]{0, 0, 0, 7}, (byte[]) Mp4Atoms.itemData(rewritten, "xu32").getFirst()[1]);
        assertEquals(22, Mp4Atoms.itemData(rewritten, "xu32").getFirst()[0]);
        assertEquals(21, Mp4Atoms.itemData(rewritten, "xs24").getFirst()[0]);
        assertArrayEquals(new byte[]{-1, -1, -2}, (byte[]) Mp4Atoms.itemData(rewritten, "xs24").getFirst()[1]);
        assertEquals(22, Mp4Atoms.itemData(rewritten, "tmpo").getFirst()[0]);
        assertArrayEquals(new byte[]{-1, -1}, (byte[]) Mp4Atoms.itemData(rewritten, "tmpo").getFirst()[1]);
        var text = TagEditor.write(data, read.toBuilder().set("xu32", "seven").build(), TagWriteOptions.defaults());
        assertEquals(1, Mp4Atoms.itemData(text, "xu32").getFirst()[0]);
        assertEquals("seven", TagEditor.read(MediaSource.of(text)).get("xu32").orElseThrow());
        var patched = data;
        assertThrows(MediaException.Unsupported.class, () -> TagEditor.write(patched,
                read.toBuilder().set("tmpo", "-1").build(), TagWriteOptions.defaults()));
    }

    @Test
    @DisplayName("keeps a known integer atom the file stores as text when it is not a number")
    void textStored() {
        var placeholders = MediaTags.builder().set("xmpo", "fast").set("xtng", "12").build();
        var data = TagEditor.write(Fixtures.read(untagged()), placeholders, TagWriteOptions.defaults());
        for (var rename : List.of(new String[]{"xmpo", "tmpo"}, new String[]{"xtng", "rtng"})) {
            var item = Mp4Atoms.all(data, "moov/udta/meta/ilst/" + rename[0]).getFirst();
            System.arraycopy(rename[1].getBytes(StandardCharsets.ISO_8859_1), 0, data, item.offset() + 4, 4);
        }
        var read = TagEditor.read(MediaSource.of(data));
        assertEquals("fast", read.get("tmpo").orElseThrow());
        assertEquals("12", read.get("rtng").orElseThrow());
        var rewritten = TagEditor.write(data, read.toBuilder().set(StandardTag.TITLE, "t").build(),
                TagWriteOptions.defaults());
        assertEquals(1, Mp4Atoms.itemData(rewritten, "tmpo").getFirst()[0], "a text tempo stays text");
        assertEquals("fast", TagEditor.read(MediaSource.of(rewritten)).get("tmpo").orElseThrow());
        assertEquals(21, Mp4Atoms.itemData(rewritten, "rtng").getFirst()[0], "a numeric text rating becomes an integer");
        assertArrayEquals(new byte[]{12}, (byte[]) Mp4Atoms.itemData(rewritten, "rtng").getFirst()[1]);
        var number = TagEditor.write(data, read.toBuilder().set("tmpo", "96").build(), TagWriteOptions.defaults());
        assertEquals(21, Mp4Atoms.itemData(number, "tmpo").getFirst()[0]);
        assertArrayEquals(new byte[]{0, 96}, (byte[]) Mp4Atoms.itemData(number, "tmpo").getFirst()[1]);
    }

    @Test
    @DisplayName("widens a known integer atom stored narrower than its width when a value needs it")
    void widens() {
        var data = TagEditor.write(Fixtures.read(untagged()), MediaTags.builder().set("xmpo", "A").build(),
                TagWriteOptions.defaults());
        var item = Mp4Atoms.all(data, "moov/udta/meta/ilst/xmpo").getFirst();
        System.arraycopy("tmpo".getBytes(StandardCharsets.ISO_8859_1), 0, data, item.offset() + 4, 4);
        data = patch(data, "tmpo", 21, new byte[]{90});
        var read = TagEditor.read(MediaSource.of(data));
        assertEquals("90", read.get("tmpo").orElseThrow());
        var same = TagEditor.write(data, read.toBuilder().set("tmpo", "100").build(), TagWriteOptions.defaults());
        assertArrayEquals(new byte[]{100}, (byte[]) Mp4Atoms.itemData(same, "tmpo").getFirst()[1], "the width is kept");
        var wide = TagEditor.write(data, read.toBuilder().set("tmpo", "200").build(), TagWriteOptions.defaults());
        assertArrayEquals(new byte[]{0, (byte) 200}, (byte[]) Mp4Atoms.itemData(wide, "tmpo").getFirst()[1]);
        assertEquals("200", TagEditor.read(MediaSource.of(wide)).get("tmpo").orElseThrow());
        assertThrows(MediaException.Unsupported.class, () -> TagEditor.write(Fixtures.read(untagged()),
                MediaTags.builder().set("tmpo", "40000").build(), TagWriteOptions.defaults()));
    }

    @Test
    @DisplayName("writes the integer atoms through Mp4Muxer")
    void muxer(@TempDir Path directory) throws Exception {
        var output = MuxedTagsTest.remux(untagged(), Mp4Muxer::new, integerTags());
        var read = TagEditor.read(MediaSource.of(output));
        for (var entry : VALUES.entrySet()) {
            assertEquals(entry.getValue(), read.get(entry.getKey()).orElseThrow(), entry.getKey());
            assertEquals(WIDTHS.get(entry.getKey()), ((byte[]) Mp4Atoms.itemData(output, entry.getKey()).getFirst()[1]).length);
        }
        var probe = TagFixtures.ffprobeTags(save(directory, output));
        assertEquals("10", probe.get("media_type"), probe.toString());
    }
}
