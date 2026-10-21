package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.mpeg.internal.TrailingTags;
import com.github.auties00.smalt.internal.tags.ApeTag;
import com.github.auties00.smalt.internal.tags.Id3v1Tag;
import com.github.auties00.smalt.internal.tags.Id3v2Tag;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers APEv1/APEv2 tags, appended and {@code SEEK}-located ID3v2.4 tags and Lyrics3 blocks
 * through {@link TagEditor} on MP3, ADTS, raw AC-3 and FLAC files. Fixtures are ffmpeg encodes
 * without tags, to which the tests append hand-built tags laid out from the APEv2 and ID3v2.4
 * specifications; ffprobe (which reads APE tags of ADTS files) and ffmpeg's WavPack muxer (which
 * writes APEv2 tags) are the black-box oracles, and every edit is checked to leave the audio bytes
 * unchanged.
 */
@DisplayName("APE and appended ID3v2 tags")
class ApeAndAppendedTagsTest {
    static Path bare(String extension, List<String> options) {
        var input = List.of("-f", "lavfi", "-i", TagFixtures.AUDIO);
        return Fixtures.ffmpeg(extension, input, options);
    }

    static byte[] bareMp3() {
        return Fixtures.read(bare("mp3", List.of("-c:a", "libmp3lame", "-b:a", "64k", "-id3v2_version", "0", "-write_xing",
                "0")));
    }

    static byte[] bareAdts() {
        return Fixtures.read(bare("aac", List.of("-c:a", "aac", "-b:a", "64k", "-f", "adts")));
    }

    static byte[] bareAc3() {
        return Fixtures.read(Fixtures.ffmpeg("ac3", List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2"),
                List.of("-c:a", "ac3", "-b:a", "96k", "-f", "ac3")));
    }

    static byte[] bareFlac() {
        return Fixtures.read(bare("flac", List.of("-c:a", "flac")));
    }

    static byte[] item(String key, int flags, byte[] value) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(value.length).putInt(flags).array());
        output.writeBytes(key.getBytes(StandardCharsets.US_ASCII));
        output.write(0);
        output.writeBytes(value);
        return output.toByteArray();
    }

    static byte[] text(String key, String value) {
        return item(key, 0, value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] apeFooter(int version, int size, int count, int flags) {
        return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).put("APETAGEX".getBytes(StandardCharsets.US_ASCII))
                .putInt(version).putInt(size).putInt(count).putInt(flags).putLong(0).array();
    }

    static byte[] ape(boolean header, byte[]... items) {
        var body = concat(items);
        var size = body.length + 32;
        return concat(header ? apeFooter(2000, size, items.length, 0xA0000000) : new byte[0], body,
                apeFooter(2000, size, items.length, header ? 0x80000000 : 0));
    }

    static byte[] cover() {
        return Fixtures.read(TagFixtures.cover());
    }

    /** The APE tag the tests append: text, multi-value text, a custom key, cover art and a binary item. */
    static byte[] sampleApe(boolean header) {
        return ape(header, text("Title", "APE Title è"), text("Artist", "Ape One\u0000Ape Two"), text("Album", "APE Album"),
                text("Year", "2019"), text("Track", "2/7"), text("REPLAYGAIN_TRACK_GAIN", "-4.20 dB"),
                item("Cover Art (Front)", 2, concat("front.png\0".getBytes(StandardCharsets.UTF_8), cover())),
                item("Private Data", 2, new byte[]{1, 2, 3, 4}));
    }

    static byte[] lyrics3v2() {
        var content = "LYRICSBEGININD0000210LYR00011hello world";
        return (content + String.format("%06d", content.length()) + "LYRICS200").getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] id3v1(String title) {
        var tag = new byte[128];
        tag[0] = 'T';
        tag[1] = 'A';
        tag[2] = 'G';
        var bytes = title.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(bytes, 0, tag, 3, bytes.length);
        System.arraycopy("V1 comment".getBytes(StandardCharsets.ISO_8859_1), 0, tag, 97, 10);
        tag[127] = (byte) 0xFF;
        return tag;
    }

    static byte[] syncsafe(int value) {
        return new byte[]{(byte) (value >> 21 & 0x7F), (byte) (value >> 14 & 0x7F), (byte) (value >> 7 & 0x7F), (byte) (value & 0x7F)};
    }

    static byte[] frame(String id, byte[] payload) {
        return concat(id.getBytes(StandardCharsets.US_ASCII), syncsafe(payload.length), new byte[2], payload);
    }

    static byte[] textFrame(String id, String value) {
        return frame(id, concat(new byte[]{3}, value.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] seekFrame(long offset) {
        return frame("SEEK", ByteBuffer.allocate(4).putInt((int) offset).array());
    }

    /** An ID3v2.4 tag; {@code footer} adds the {@code "3DI"} footer, {@code update} an extended header with the update flag. */
    static byte[] id3v24(boolean footer, boolean update, byte[]... frames) {
        var body = concat(update ? new byte[]{0, 0, 0, 6, 1, 0x40} : new byte[0], concat(frames));
        var flags = (footer ? 0x10 : 0) | (update ? 0x40 : 0);
        var header = concat(new byte[]{'I', 'D', '3', 4, 0, (byte) flags}, syncsafe(body.length));
        if (!footer) {
            return concat(header, body);
        }
        var trailer = header.clone();
        trailer[0] = '3';
        trailer[1] = 'D';
        trailer[2] = 'I';
        return concat(header, body, trailer);
    }

    static byte[] concat(byte[]... parts) {
        var output = new ByteArrayOutputStream();
        for (var part : parts) {
            output.writeBytes(part);
        }
        return output.toByteArray();
    }

    /** Returns the bytes between the leading ID3v2 tags and the trailing tags. */
    static byte[] audioOf(byte[] file) {
        var start = 0L;
        while (file.length - start >= 10) {
            var size = Id3v2Tag.size(file, (int) start);
            if (size < 0) {
                break;
            }
            start += size;
        }
        var end = TrailingTags.audioEnd(MediaSource.of(file), start, file.length);
        return Arrays.copyOfRange(file, (int) start, (int) end);
    }

    /** Returns the offset of the first frame of a native FLAC file, after its metadata blocks. */
    static int flacFrames(byte[] file) {
        assertEquals('f', file[0]);
        var position = 4;
        var last = false;
        while (!last) {
            last = (file[position] & 0x80) != 0;
            position += 4 + ((file[position + 1] & 0xFF) << 16 | (file[position + 2] & 0xFF) << 8 | file[position + 3] & 0xFF);
        }
        return position;
    }

    static List<TrailingTags.Region> trailing(byte[] file, long start) {
        return TrailingTags.regions(MediaSource.of(file), start, file.length);
    }

    static byte[] region(byte[] file, TrailingTags.Region region) {
        return Arrays.copyOfRange(file, (int) region.offset(), (int) region.end());
    }

    static TrailingTags.Region only(List<TrailingTags.Region> regions, TrailingTags.Kind kind) {
        var matching = regions.stream().filter(region -> region.kind() == kind).toList();
        assertEquals(1, matching.size(), "regions " + regions);
        return matching.getFirst();
    }

    static List<byte[]> packets(byte[] file) {
        var result = new ArrayList<byte[]>();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                result.add(packet.data());
            }
        }
        return result;
    }

    static void assertSamePackets(byte[] expected, byte[] actual) {
        var first = packets(expected);
        var second = packets(actual);
        assertEquals(first.size(), second.size(), "packet count");
        for (var index = 0; index < first.size(); index++) {
            assertArrayEquals(first.get(index), second.get(index), "packet " + index);
        }
    }

    static Path write(Path directory, String name, byte[] data) {
        try {
            var file = directory.resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    static final TagWriteOptions ALWAYS = TagWriteOptions.builder().apeMode(TagWriteOptions.ApeMode.ALWAYS).build();

    @Nested
    @DisplayName("reading APE tags")
    class ReadingApe {
        @ParameterizedTest(name = "header {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("reads text, multi-value text, custom keys and cover art from an MP3")
        void mp3(boolean header) {
            var file = concat(bareMp3(), sampleApe(header));
            var tags = TagEditor.read(MediaSource.of(file));
            assertEquals("APE Title è", tags.get(StandardTag.TITLE).orElseThrow());
            assertEquals(List.of("Ape One", "Ape Two"), tags.getAll(StandardTag.ARTIST));
            assertEquals("APE Album", tags.get(StandardTag.ALBUM).orElseThrow());
            assertEquals("2019", tags.get(StandardTag.DATE).orElseThrow());
            assertEquals("2/7", tags.get(StandardTag.TRACK).orElseThrow());
            assertEquals("-4.20 dB", tags.get("REPLAYGAIN_TRACK_GAIN").orElseThrow());
            assertEquals(1, tags.pictures().size());
            var picture = tags.pictures().getFirst();
            assertEquals(AttachedPicture.Type.FRONT_COVER, picture.type());
            assertEquals("front", picture.description());
            assertEquals("image/png", picture.mediaType());
            assertArrayEquals(cover(), picture.data());
        }

        @Test
        @DisplayName("reads an APE tag before Lyrics3 and ID3v1, which only adds missing keys")
        void beforeOtherTags() {
            var file = concat(bareMp3(), sampleApe(true), lyrics3v2(), id3v1("V1 Title"));
            var tags = TagEditor.read(MediaSource.of(file));
            assertEquals("APE Title è", tags.get(StandardTag.TITLE).orElseThrow());
            assertEquals("V1 comment", tags.get(StandardTag.COMMENT).orElseThrow());
            assertEquals(1, tags.pictures().size());
        }

        @Test
        @DisplayName("lets a leading ID3v2 tag win over the APE tag key by key, pictures included")
        void id3Precedence() {
            var id3 = id3v24(false, false, textFrame("TIT2", "ID3 title"));
            var file = concat(id3, bareMp3(), sampleApe(true));
            var tags = TagEditor.read(MediaSource.of(file));
            assertEquals(List.of("ID3 title"), tags.getAll(StandardTag.TITLE));
            assertEquals("APE Album", tags.get(StandardTag.ALBUM).orElseThrow());
            assertEquals(1, tags.pictures().size(), "APE cover used while ID3v2 has none");
            var withPicture = TagEditor.write(file, MediaTags.builder().set(StandardTag.TITLE, "x")
                    .addPicture(TagFixtures.picture(AttachedPicture.Type.BACK_COVER, "id3")).build(),
                    TagWriteOptions.builder().apeMode(TagWriteOptions.ApeMode.REMOVE).build());
            var both = concat(withPicture, sampleApe(false));
            var pictures = TagEditor.read(MediaSource.of(both)).pictures();
            assertEquals(List.of(AttachedPicture.Type.BACK_COVER), pictures.stream().map(AttachedPicture::type).toList());
        }

        @Test
        @DisplayName("reads APE tags of ADTS, raw AC-3 and FLAC files")
        void otherContainers() {
            for (var bare : List.of(bareAdts(), bareAc3(), bareFlac())) {
                var file = concat(bare, sampleApe(true));
                var tags = TagEditor.read(MediaSource.of(file));
                assertEquals("APE Title è", tags.get(StandardTag.TITLE).orElseThrow());
                assertEquals(List.of("Ape One", "Ape Two"), tags.getAll(StandardTag.ARTIST));
                assertEquals(1, tags.pictures().size());
                assertArrayEquals(cover(), tags.pictures().getFirst().data());
            }
        }

        @Test
        @DisplayName("reports the APE fields and cover art through Media.probe and the demuxers")
        void probe() {
            for (var bare : List.of(bareMp3(), bareAdts(), bareAc3(), bareFlac())) {
                var file = concat(bare, sampleApe(false), id3v1("V1 Title"));
                var info = Media.probe(MediaSource.of(file));
                assertEquals("APE Title è", info.tags().get("title"), info.container().toString());
                assertEquals("APE Album", info.tags().get("album"));
                assertEquals("V1 comment", info.tags().get("comment"));
                assertEquals("-4.20 dB", info.tags().get("REPLAYGAIN_TRACK_GAIN"));
                assertEquals(1, info.attachedPictures().size());
                assertSamePackets(bare, file);
            }
        }

        @Test
        @DisplayName("reads the APEv2 tags ffmpeg writes, as ffprobe reads them")
        void ffmpegWritten() {
            var wv = TagFixtures.audio("wv", List.of("-c:a", "wavpack"));
            var wavPack = Fixtures.read(wv);
            var apeRegion = only(trailing(wavPack, 0), TrailingTags.Kind.APE);
            var apeBytes = region(wavPack, apeRegion);
            var probe = TagFixtures.ffprobeTags(wv);
            for (var bare : List.of(bareMp3(), bareAdts(), bareAc3(), bareFlac())) {
                var tags = TagEditor.read(MediaSource.of(concat(bare, apeBytes)));
                assertEquals(probe.get("title"), tags.get(StandardTag.TITLE).orElse(null));
                assertEquals(probe.get("artist"), tags.get(StandardTag.ARTIST).orElse(null));
                assertEquals(probe.get("album"), tags.get(StandardTag.ALBUM).orElse(null));
                assertEquals(probe.get("genre"), tags.get(StandardTag.GENRE).orElse(null));
                assertEquals(probe.get("date"), tags.get(StandardTag.DATE).orElse(null));
                assertEquals(probe.get("track"), tags.get(StandardTag.TRACK).orElse(null));
                assertEquals(probe.get("comment"), tags.get(StandardTag.COMMENT).orElse(null));
                assertNotNull(probe.get("title"));
            }
        }
    }

    @Nested
    @DisplayName("writing APE tags")
    class WritingApe {
        @Test
        @DisplayName("updates an existing APE tag in place, keeping Lyrics3 and the audio bytes, and ffprobe reads it")
        void updateExisting(@TempDir Path directory) {
            var bare = bareMp3();
            var file = write(directory, "update.mp3", concat(bare, sampleApe(true), lyrics3v2(), id3v1("V1 Title")));
            assertEquals(TagWriteResult.REWRITTEN, TagEditor.write(file, TagFixtures.sample()));
            assertEquals(TagWriteResult.IN_PLACE, TagEditor.write(file, TagFixtures.sample().toBuilder()
                    .set(StandardTag.TITLE, "Second, longer edit title").build()));
            var edited = Fixtures.read(file);
            assertArrayEquals(bare, audioOf(edited));
            var regions = trailing(edited, Id3v2Tag.size(edited, 0));
            assertEquals(List.of(TrailingTags.Kind.APE, TrailingTags.Kind.LYRICS3, TrailingTags.Kind.ID3V1),
                    regions.stream().map(TrailingTags.Region::kind).toList());
            assertArrayEquals(lyrics3v2(), region(edited, regions.get(1)));
            var ape = ApeTag.parse(region(edited, regions.get(0)));
            var content = ape.decode();
            var mirrored = MediaTags.builder();
            content.entries().forEach(entry -> mirrored.add(entry.key(), entry.value()));
            var expected = TagFixtures.sample().toBuilder().set(StandardTag.TITLE, "Second, longer edit title").build();
            assertEquals(expected.entries(), mirrored.build().entries());
            assertEquals(expected.pictures(), content.pictures());
            assertEquals(List.of("Private Data"), content.opaque().stream().map(ApeTag.Item::key).toList(),
                    "binary item preserved");
            var v1 = Id3v1Tag.parse(region(edited, regions.get(2)), 0);
            assertTrue(v1.contains(new MediaTags.Entry("title", "Second, longer edit title")));
            assertEquals(expected.entries(), TagEditor.read(file).entries());
            assertEquals(TagFixtures.packetHash(write(directory, "bare.mp3", bare)), TagFixtures.packetHash(file));
        }

        @Test
        @DisplayName("removes the APE tag and drops its binary items when unknown items are not preserved")
        void removeAndPreserve() {
            var file = concat(bareMp3(), sampleApe(true), id3v1("V1 Title"));
            var removed = TagEditor.write(file, TagFixtures.sample(),
                    TagWriteOptions.builder().apeMode(TagWriteOptions.ApeMode.REMOVE).build());
            var regions = trailing(removed, Id3v2Tag.size(removed, 0));
            assertEquals(List.of(TrailingTags.Kind.ID3V1), regions.stream().map(TrailingTags.Region::kind).toList());
            assertArrayEquals(bareMp3(), audioOf(removed));
            var stripped = TagEditor.write(file, TagFixtures.sample(), TagWriteOptions.builder().preserveUnknown(false).build());
            var ape = ApeTag.parse(region(stripped, only(trailing(stripped, Id3v2Tag.size(stripped, 0)), TrailingTags.Kind.APE)));
            assertTrue(ape.decode().opaque().isEmpty());
            var empty = TagEditor.write(file, MediaTags.empty(), TagWriteOptions.builder().preserveUnknown(false).build());
            assertArrayEquals(bareMp3(), empty, "nothing left to store");
        }

        @Test
        @DisplayName("adds an APE tag only when asked, before Lyrics3 and ID3v1")
        void always() {
            var bare = bareMp3();
            var file = concat(bare, lyrics3v2(), id3v1("V1 Title"));
            var defaults = TagEditor.write(file, TagFixtures.sample(), TagWriteOptions.defaults());
            assertTrue(trailing(defaults, Id3v2Tag.size(defaults, 0)).stream().noneMatch(region -> region.kind() == TrailingTags.Kind.APE));
            var added = TagEditor.write(file, TagFixtures.sample(), ALWAYS);
            var regions = trailing(added, Id3v2Tag.size(added, 0));
            assertEquals(List.of(TrailingTags.Kind.APE, TrailingTags.Kind.LYRICS3, TrailingTags.Kind.ID3V1),
                    regions.stream().map(TrailingTags.Region::kind).toList());
            assertArrayEquals(bare, audioOf(added));
            var apeOnly = concat(bare, region(added, regions.getFirst()));
            var read = TagEditor.read(MediaSource.of(apeOnly));
            assertEquals(TagFixtures.sample().entries(), read.entries());
            assertEquals(1, read.pictures().size());
        }

        @Test
        @DisplayName("writes an APE tag to ADTS that ffprobe reads, cover art included")
        void ffprobeReadsAdts(@TempDir Path directory) {
            var bare = bareAdts();
            var edited = TagEditor.write(bare, TagFixtures.sample(), ALWAYS);
            assertArrayEquals(bare, audioOf(edited));
            var apeOnly = Arrays.copyOfRange(edited, (int) Id3v2Tag.size(edited, 0), edited.length);
            var file = write(directory, "ape.aac", apeOnly);
            var probe = TagFixtures.ffprobeTags(file);
            var sample = TagFixtures.sample();
            assertEquals(sample.get(StandardTag.TITLE).orElseThrow(), probe.get("title"));
            assertEquals("First Artist", probe.get("artist"), "ffmpeg keeps the first of the NUL-separated values");
            assertEquals(sample.get(StandardTag.ALBUM).orElseThrow(), probe.get("album"));
            assertEquals(sample.get(StandardTag.ALBUM_ARTIST).orElseThrow(), probe.get("album_artist"));
            assertEquals(sample.get(StandardTag.DATE).orElseThrow(), probe.get("year"));
            assertEquals(sample.get(StandardTag.TRACK).orElseThrow(), probe.get("track"));
            assertEquals(sample.get("REPLAYGAIN_TRACK_GAIN").orElseThrow(), probe.get("replaygain_track_gain"));
            assertEquals(1, TagFixtures.ffprobeAttachedPictures(file));
            var picture = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-i", file.toString(),
                    "-map", "0:v", "-c", "copy", "-f", "image2pipe", "-"));
            assertArrayEquals(sample.pictures().getFirst().data(), picture);
            assertEquals(TagFixtures.packetHash(write(directory, "bare.aac", bare)), TagFixtures.packetHash(file));
        }

        @Test
        @DisplayName("edits the APE and ID3v1 tags of raw AC-3 and FLAC files without touching the audio")
        void ac3AndFlac(@TempDir Path directory) {
            var ac3 = bareAc3();
            var taggedAc3 = TagEditor.write(ac3, TagFixtures.sample(), TagWriteOptions.builder()
                    .apeMode(TagWriteOptions.ApeMode.ALWAYS).id3v1Mode(TagWriteOptions.Id3v1Mode.ALWAYS).build());
            assertEquals(ContainerFormat.AC3, Containers.detect(MediaSource.of(taggedAc3)).orElseThrow());
            assertTrue(TagEditor.isSupported(ContainerFormat.AC3));
            assertArrayEquals(ac3, audioOf(taggedAc3));
            assertSamePackets(ac3, taggedAc3);
            var readAc3 = TagEditor.read(MediaSource.of(taggedAc3));
            assertEquals(TagFixtures.sample().entries(), readAc3.entries());
            assertEquals(TagFixtures.sample().pictures(), readAc3.pictures());
            assertEquals(3, readAc3.chapters().size());
            var info = Media.probe(MediaSource.of(taggedAc3));
            assertEquals(TagFixtures.sample().get(StandardTag.TITLE).orElseThrow(), info.tags().get("title"));
            assertEquals(3, info.chapters().size());
            assertEquals(1, info.attachedPictures().size());

            var flac = concat(bareFlac(), sampleApe(true), id3v1("V1 Title"));
            var before = TagEditor.read(MediaSource.of(flac));
            assertEquals("APE Title è", before.get(StandardTag.TITLE).orElseThrow());
            var editedFlac = TagEditor.write(flac, TagFixtures.sample(), TagWriteOptions.defaults());
            var flacRegions = trailing(editedFlac, 0);
            assertEquals(List.of(TrailingTags.Kind.APE, TrailingTags.Kind.ID3V1),
                    flacRegions.stream().map(TrailingTags.Region::kind).toList());
            var apeContent = ApeTag.parse(region(editedFlac, flacRegions.getFirst())).decode();
            assertEquals("Edited Album", apeContent.entries().stream().filter(entry -> entry.key().equals("album"))
                    .findFirst().orElseThrow().value());
            var read = TagEditor.read(MediaSource.of(editedFlac));
            assertEquals(TagFixtures.sample().entries(), read.entries());
            var frames = flacFrames(bareFlac());
            var editedFrames = flacFrames(editedFlac);
            assertArrayEquals(Arrays.copyOfRange(bareFlac(), frames, bareFlac().length),
                    Arrays.copyOfRange(editedFlac, editedFrames, (int) flacRegions.getFirst().offset()));
            assertSamePackets(bareFlac(), editedFlac);
            assertEquals(TagFixtures.packetHash(write(directory, "bare.flac", bareFlac())), TagFixtures.packetHash(write(directory,
                    "edited.flac", Arrays.copyOf(editedFlac, (int) flacRegions.getFirst().offset()))));
            var plainFlac = TagEditor.write(bareFlac(), TagFixtures.sample(), TagWriteOptions.defaults());
            assertTrue(trailing(plainFlac, 0).isEmpty(), "a FLAC file without trailing tags gets none");
        }
    }

    @Nested
    @DisplayName("Lyrics3 blocks and malformed APE footers")
    class Lyrics3AndFooters {
        static byte[] lyrics3v1() {
            return "LYRICSBEGIN[00:01]la la la LYRICSEND".getBytes(StandardCharsets.US_ASCII);
        }

        @Test
        @DisplayName("keeps a Lyrics3v1 block while an ID3v1 tag follows it and drops it with that tag")
        void lyrics3v1Kept() {
            var bare = bareMp3();
            var file = concat(bare, lyrics3v1(), id3v1("V1 Title"));
            for (var flac : new boolean[]{false, true}) {
                var input = flac ? concat(bareFlac(), lyrics3v1(), id3v1("V1 Title")) : file;
                var start = flac ? 0 : -1;
                var kept = TagEditor.write(input, TagFixtures.sample(), ALWAYS);
                var keptRegions = trailing(kept, start < 0 ? Id3v2Tag.size(kept, 0) : 0);
                assertEquals(List.of(TrailingTags.Kind.APE, TrailingTags.Kind.LYRICS3, TrailingTags.Kind.ID3V1),
                        keptRegions.stream().map(TrailingTags.Region::kind).toList());
                assertArrayEquals(lyrics3v1(), region(kept, keptRegions.get(1)));
                var dropped = TagEditor.write(input, TagFixtures.sample(),
                        TagWriteOptions.builder().id3v1Mode(TagWriteOptions.Id3v1Mode.REMOVE).build());
                var droppedRegions = trailing(dropped, start < 0 ? Id3v2Tag.size(dropped, 0) : 0);
                assertTrue(droppedRegions.isEmpty(), "regions " + droppedRegions);
                if (!flac) {
                    assertArrayEquals(bare, audioOf(dropped), "no orphaned Lyrics3v1 bytes before the end");
                }
                var lyrics3v2 = TagEditor.write(flac ? concat(bareFlac(), lyrics3v2(), id3v1("V1")) : concat(bare, lyrics3v2(),
                        id3v1("V1")), TagFixtures.sample(), TagWriteOptions.builder().id3v1Mode(TagWriteOptions.Id3v1Mode.REMOVE)
                        .build());
                var v2Regions = trailing(lyrics3v2, start < 0 ? Id3v2Tag.size(lyrics3v2, 0) : 0);
                assertEquals(List.of(TrailingTags.Kind.LYRICS3), v2Regions.stream().map(TrailingTags.Region::kind).toList());
            }
        }

        @Test
        @DisplayName("does not cut into the audio when an APE footer flags a header the tag lacks")
        void headerFlagWithoutHeader() {
            var bare = bareMp3();
            var tag = ape(false, text("Title", "Footer only"));
            tag[tag.length - 32 + 20 + 3] = (byte) 0x80;
            var file = concat(bare, tag);
            assertEquals("Footer only", TagEditor.read(MediaSource.of(file)).get(StandardTag.TITLE).orElseThrow());
            var edited = TagEditor.write(file, TagFixtures.sample(), TagWriteOptions.defaults());
            assertArrayEquals(bare, audioOf(edited));
            assertSamePackets(bare, edited);
            assertEquals(TagFixtures.sample().entries(), TagEditor.read(MediaSource.of(edited)).entries());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp3", "adts", "ac3", "flac"})
    @DisplayName("mutated trailing and SEEK-located tags raise nothing but MediaException")
    void robustness(String container) {
        var bare = switch (container) {
            case "mp3" -> bareMp3();
            case "adts" -> bareAdts();
            case "ac3" -> bareAc3();
            default -> bareFlac();
        };
        var appended = id3v24(true, true, textFrame("TALB", "Appended"), textFrame("TIT2", "Appended title"));
        var leading = container.equals("flac") ? new byte[0] : id3v24(false, false, textFrame("TIT2", "Lead"),
                seekFrame(bare.length));
        var file = concat(leading, bare, appended, sampleApe(true), lyrics3v2(), id3v1("V1"));
        var tail = Math.min(file.length, appended.length + sampleApe(true).length + lyrics3v2().length + 128 + 64);
        var random = new java.util.Random(container.hashCode());
        var limits = com.github.auties00.smalt.MediaLimits.builder().maxBytes(16 << 20).build();
        var options = TagWriteOptions.builder().limits(limits).apeMode(TagWriteOptions.ApeMode.ALWAYS).build();
        for (var round = 0; round < 200; round++) {
            var mutated = file.clone();
            for (var flips = 0; flips < 1 + random.nextInt(8); flips++) {
                var at = random.nextInt(8) == 0 ? random.nextInt(Math.min(mutated.length, 64))
                        : mutated.length - 1 - random.nextInt(tail);
                mutated[at] = random.nextInt(3) == 0 ? (byte) 0xFF : (byte) random.nextInt(256);
            }
            if (random.nextInt(6) == 0) {
                mutated = Arrays.copyOf(mutated, mutated.length - 1 - random.nextInt(tail));
            }
            var input = mutated;
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                TagRobustnessTest.attempt(() -> TagEditor.read(MediaSource.of(input), TagReadOptions.builder().limits(limits).build()));
                TagRobustnessTest.attempt(() -> TagEditor.write(input, TagFixtures.sample(), options));
                TagRobustnessTest.attempt(() -> TagEditor.write(input, MediaTags.empty(), TagWriteOptions.builder().limits(limits)
                        .build()));
                TagRobustnessTest.attempt(() -> Media.probe(MediaSource.of(input)));
            });
        }
    }

    @Nested
    @DisplayName("appended and SEEK-located ID3v2 tags")
    class Appended {
        /** Converts a leading ID3v2.4 tag into an appended one by setting the footer flag and adding the footer. */
        static byte[] toAppended(byte[] tag) {
            var copy = tag.clone();
            copy[5] |= 0x10;
            var trailer = Arrays.copyOf(copy, 10);
            trailer[0] = '3';
            trailer[1] = 'D';
            trailer[2] = 'I';
            return concat(copy, trailer);
        }

        @Test
        @DisplayName("reads an ffmpeg ID3v2.4 tag moved to the end of the file as the leading tag reads")
        void appendedTag(@TempDir Path directory) {
            var original = Fixtures.read(TagFixtures.audio("mp3", List.of("-c:a", "libmp3lame", "-b:a", "64k")));
            var size = (int) Id3v2Tag.size(original, 0);
            assertEquals(4, original[3]);
            var audio = Arrays.copyOfRange(original, size, original.length);
            var appended = concat(audio, toAppended(Arrays.copyOf(original, size)), id3v1("V1 Title"));
            var expected = TagEditor.read(MediaSource.of(original));
            var probe = TagFixtures.ffprobeTags(TagFixtures.audio("mp3", List.of("-c:a", "libmp3lame", "-b:a", "64k")));
            assertEquals(probe.get("title"), expected.get(StandardTag.TITLE).orElseThrow());
            var read = TagEditor.read(MediaSource.of(appended));
            assertEquals(expected.entries(), read.entries());
            assertEquals(expected.chapters(), read.chapters());
            assertEquals(probe.get("title"), Media.probe(MediaSource.of(appended)).tags().get("title"));
            var file = write(directory, "appended.mp3", appended);
            TagEditor.write(file, read);
            var rewritten = Fixtures.read(file);
            assertTrue(trailing(rewritten, Id3v2Tag.size(rewritten, 0)).stream()
                    .noneMatch(region -> region.kind() == TrailingTags.Kind.ID3V2), "appended tag merged into the leading tag");
            assertArrayEquals(audio, audioOf(rewritten));
            assertEquals(read.entries(), TagEditor.read(file).entries());
            assertEquals(read.chapters(), TagEditor.read(file).chapters());
        }

        @Test
        @DisplayName("lets an appended tag discard the leading tag, or update it when flagged as an update (ID3v2.4 section 5)")
        void update() {
            var leading = id3v24(false, false, textFrame("TIT2", "Old"), textFrame("TALB", "Album"));
            var plain = concat(leading, bareMp3(), id3v24(true, false, textFrame("TIT2", "Replaced"), textFrame("TPE1", "Added")));
            var plainTags = TagEditor.read(MediaSource.of(plain));
            assertEquals(List.of(new MediaTags.Entry("title", "Replaced"), new MediaTags.Entry("artist", "Added")),
                    plainTags.entries());
            assertEquals(Map.of("title", "Replaced", "artist", "Added"), Media.probe(MediaSource.of(plain)).tags());
            var update = concat(leading, bareMp3(), id3v24(true, true, textFrame("TIT2", "New")));
            var updated = TagEditor.read(MediaSource.of(update));
            assertEquals(List.of("New"), updated.getAll(StandardTag.TITLE));
            assertEquals("Album", updated.get(StandardTag.ALBUM).orElseThrow());
            assertEquals("New", Media.probe(MediaSource.of(update)).tags().get("title"));
            assertEquals("Album", Media.probe(MediaSource.of(update)).tags().get("album"));
            var pictured = TagEditor.write(concat(bareMp3(), id3v1("V1")), MediaTags.builder().set(StandardTag.TITLE, "Old")
                    .addPicture(TagFixtures.picture(AttachedPicture.Type.BACK_COVER, "lead")).build(),
                    TagWriteOptions.builder().id3v1Mode(TagWriteOptions.Id3v1Mode.REMOVE).build());
            var textOnlyUpdate = concat(pictured, id3v24(true, true, textFrame("TIT2", "New")));
            assertEquals(1, TagEditor.read(MediaSource.of(textOnlyUpdate)).pictures().size(), "an update without pictures keeps them");
            assertEquals(1, Media.probe(MediaSource.of(textOnlyUpdate)).attachedPictures().size());
            var textOnlyPlain = concat(pictured, id3v24(true, false, textFrame("TIT2", "New")));
            assertTrue(TagEditor.read(MediaSource.of(textOnlyPlain)).pictures().isEmpty(), "a plain tag discards them");
            assertTrue(Media.probe(MediaSource.of(textOnlyPlain)).attachedPictures().isEmpty());
        }

        @Test
        @DisplayName("follows a SEEK frame to a tag inside the audio and removes that tag on a rewrite")
        void seekInsideAudio() {
            var bare = bareMp3();
            var frames = packets(bare);
            var boundary = 0;
            for (var index = 0; index < 5; index++) {
                boundary += frames.get(index).length;
            }
            var mid = id3v24(false, false, textFrame("TALB", "Seek album"), textFrame("TIT2", "Seek title"));
            var leading = id3v24(false, false, textFrame("TIT2", "Lead title"), seekFrame(boundary));
            var file = concat(leading, Arrays.copyOf(bare, boundary), mid, Arrays.copyOfRange(bare, boundary, bare.length));
            var tags = TagEditor.read(MediaSource.of(file));
            assertEquals(List.of("Seek title"), tags.getAll(StandardTag.TITLE), "the located tag replaces the leading one");
            assertEquals("Seek album", tags.get(StandardTag.ALBUM).orElseThrow());
            var probe = Media.probe(MediaSource.of(file)).tags();
            assertEquals(Map.of("title", "Seek title", "album", "Seek album"), probe);
            var rewritten = TagEditor.write(file, tags, TagWriteOptions.defaults());
            assertArrayEquals(bare, audioOf(rewritten), "the located tag is cut from the audio");
            var tag = Id3v2Tag.parse(Arrays.copyOf(rewritten, (int) Id3v2Tag.size(rewritten, 0)), com.github.auties00.smalt.MediaLimits.DEFAULT);
            assertEquals(-1, tag.seekOffset(), "the SEEK frame is dropped");
            assertEquals(tags.entries(), TagEditor.read(MediaSource.of(rewritten)).entries());
            assertSamePackets(bare, rewritten);
        }

        @Test
        @DisplayName("follows a SEEK frame to the appended tag without reading it twice")
        void seekToAppended() {
            var bare = bareMp3();
            var appended = id3v24(true, false, textFrame("TALB", "Appended album"), textFrame("TCON", "Jazz"));
            var leading = id3v24(false, false, textFrame("TIT2", "Lead"), seekFrame(bare.length));
            var file = concat(leading, bare, appended);
            var tags = TagEditor.read(MediaSource.of(file));
            assertEquals(List.of(new MediaTags.Entry("album", "Appended album"), new MediaTags.Entry("genre", "Jazz")),
                    tags.entries());
            assertEquals(Map.of("album", "Appended album", "genre", "Jazz"), Media.probe(MediaSource.of(file)).tags());
            var rewritten = TagEditor.write(file, tags, TagWriteOptions.defaults());
            assertArrayEquals(bare, audioOf(rewritten));
            assertTrue(trailing(rewritten, Id3v2Tag.size(rewritten, 0)).isEmpty());
        }

        @Test
        @DisplayName("lets an update tag a SEEK frame locates replace only the values it holds")
        void seekUpdate() {
            var bare = bareMp3();
            var boundary = 0;
            for (var frame : packets(bare).subList(0, 5)) {
                boundary += frame.length;
            }
            var mid = id3v24(false, true, textFrame("TIT2", "Seek title"));
            var leading = id3v24(false, false, textFrame("TIT2", "Lead title"), textFrame("TALB", "Lead album"),
                    seekFrame(boundary));
            var file = concat(leading, Arrays.copyOf(bare, boundary), mid, Arrays.copyOfRange(bare, boundary, bare.length));
            var tags = TagEditor.read(MediaSource.of(file));
            assertEquals(List.of(new MediaTags.Entry("title", "Seek title"), new MediaTags.Entry("album", "Lead album")),
                    tags.entries());
            assertEquals(Map.of("title", "Seek title", "album", "Lead album"), Media.probe(MediaSource.of(file)).tags());
        }

        @Test
        @DisplayName("ignores SEEK frames that point at no valid tag")
        void badSeek() {
            var bare = bareMp3();
            for (var offset : new long[]{3, bare.length - 4, bare.length + 1_000_000L, 0xFFFFFFFFL}) {
                var leading = id3v24(false, false, textFrame("TIT2", "Lead"), seekFrame(offset));
                var file = concat(leading, bare);
                assertEquals(List.of(new MediaTags.Entry("title", "Lead")), TagEditor.read(MediaSource.of(file)).entries());
                assertArrayEquals(bare, audioOf(TagEditor.write(file, TagEditor.read(MediaSource.of(file)), TagWriteOptions.defaults())));
            }
        }
    }
}
