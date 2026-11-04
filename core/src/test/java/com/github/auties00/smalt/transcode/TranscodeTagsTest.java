package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Remuxer;
import com.github.auties00.smalt.container.mp4.RemuxOptions;
import com.github.auties00.smalt.container.packaging.PackageOutput;
import com.github.auties00.smalt.container.packaging.Packager;
import com.github.auties00.smalt.container.packaging.PackagingOptions;
import com.github.auties00.smalt.container.packaging.Rendition;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagMode;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.metadata.TrackTags;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.TrackDisposition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transcodes tagged inputs into every output container that stores tags and reads the output back with
 * {@link TagEditor} and, as the independent reader, ffprobe: tags, cover bytes, chapters (cut to the kept range),
 * track tags and dispositions, and the copy, drop, replace and merge modes, through the transcoder's general path,
 * its MP4 remux path, {@link Mp4Remuxer} and {@link Packager}.
 */
@DisplayName("Tags, cover art and chapters through a transcode")
class TranscodeTagsTest {
    private static final Path DIRECTORY = Scratch.directory("transcode-tags");

    /**
     * The chapters of the tagged inputs, which last three seconds.
     */
    private static final List<Chapter> CHAPTERS = List.of(Chapter.of(0, 1_000_000, "Opening"),
            Chapter.of(1_000_000, 2_000_000, "Middle"), Chapter.of(2_000_000, 3_000_000, "Closing"));

    /**
     * The kinds of untagged inputs, each made by ffmpeg as a test vector encoder.
     */
    enum Input {
        MP3("mp3", List.of("-c:a", "libmp3lame", "-write_xing", "0")),
        FLAC("flac", List.of("-c:a", "flac")),
        OPUS("opus", List.of("-c:a", "libopus")),
        ADTS("aac", List.of("-c:a", "aac", "-f", "adts")),
        M4A("m4a", List.of("-c:a", "aac")),
        WAV("wav", List.of("-c:a", "pcm_s16le")),
        AC3("ac3", List.of("-c:a", "ac3", "-b:a", "96k")),
        EAC3("eac3", List.of("-c:a", "eac3", "-b:a", "96k")),
        AMR("amr", List.of("-ar", "8000", "-ac", "1", "-c:a", "libopencore_amrnb", "-b:a", "12.2k"));

        private final String extension;
        private final List<String> options;

        Input(String extension, List<String> options) {
            this.extension = extension;
            this.options = options;
        }

        byte[] untagged() {
            if (this == AMR) {
                Oracle.assumeEncoder("libopencore_amrnb");
            }
            var output = new ArrayList<>(List.of("-map_metadata", "-1", "-ac", this == AMR ? "1" : "2"));
            output.addAll(options);
            return Fixtures.read(Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=" + (this == AMR ? 8000 : 48000) + ":duration=3"), output));
        }

        byte[] tagged() {
            return TagEditor.write(untagged(), sample(), TagWriteOptions.defaults());
        }
    }

    static byte[] cover() {
        return Fixtures.read(Fixtures.testImage("png", 32, 24, List.of()));
    }

    /**
     * Tags with multiple values, non-Latin text, a key outside the shared vocabulary, a cover and chapters.
     */
    static MediaTags sample() {
        return MediaTags.builder()
                .set(StandardTag.TITLE, "Input Title è中")
                .set(StandardTag.ARTIST, "First Artist", "Second Artist")
                .set(StandardTag.ALBUM, "Input Album")
                .set(StandardTag.GENRE, "Ambient")
                .set(StandardTag.TRACK, "3/12")
                .set("MOOD", "calm")
                .addPicture(AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/png", "front", cover()))
                .chapters(CHAPTERS)
                .build();
    }

    static byte[] transcode(byte[] input, TranscodeOptions options) {
        var sink = MediaSink.memory();
        Transcoder.transcode(MediaSource.of(input), sink, options);
        return sink.toByteArray();
    }

    static Path save(String name, byte[] data) {
        try {
            var file = DIRECTORY.resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static Map<String, String> ffprobe(Path file) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-show_format", "-show_streams", "-show_chapters", "-of", "flat", file.toString())), StandardCharsets.UTF_8);
        var result = new HashMap<String, String>();
        for (var line : output.split("\\R")) {
            var separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            var value = line.substring(separator + 1);
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            result.put(line.substring(0, separator), value);
        }
        return result;
    }

    /**
     * Returns the file's tags as ffprobe reports them, keys in lower case, completed by the first stream's (Ogg).
     */
    static Map<String, String> ffprobeTags(Map<String, String> probe) {
        var result = new HashMap<String, String>();
        for (var entry : probe.entrySet()) {
            if (entry.getKey().startsWith("format.tags.")) {
                result.put(entry.getKey().substring(12).toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        for (var entry : probe.entrySet()) {
            if (entry.getKey().startsWith("streams.stream.0.tags.")) {
                result.putIfAbsent(entry.getKey().substring(22).toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        return result;
    }

    static List<Chapter> ffprobeChapters(Map<String, String> probe) {
        var result = new ArrayList<Chapter>();
        for (var index = 0; probe.containsKey("chapters.chapter." + index + ".start_time"); index++) {
            var prefix = "chapters.chapter." + index + ".";
            result.add(Chapter.of(micros(probe.get(prefix + "start_time")), micros(probe.get(prefix + "end_time")),
                    probe.get(prefix + "tags.title")));
        }
        return result;
    }

    static int ffprobePictures(Map<String, String> probe) {
        var count = 0;
        for (var index = 0; probe.containsKey("streams.stream." + index + ".index"); index++) {
            if ("1".equals(probe.get("streams.stream." + index + ".disposition.attached_pic"))) {
                count++;
            }
        }
        return count;
    }

    static long micros(String seconds) {
        return Math.round(Double.parseDouble(seconds) * 1_000_000);
    }

    /**
     * Returns the bytes of the first attached picture of a file, as ffmpeg extracts them.
     */
    static byte[] ffmpegCover(Path file) {
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-i", file.toString(),
                "-map", "0:v:0", "-c", "copy", "-f", "image2pipe", "-"));
    }

    static List<String> titles(List<Chapter> chapters) {
        return chapters.stream().map(chapter -> chapter.title().orElse("")).toList();
    }

    static List<Long> starts(List<Chapter> chapters) {
        return chapters.stream().map(Chapter::startMicros).toList();
    }

    /**
     * Asserts that two chapter lists agree to the millisecond, except the end of the last chapter, which a container
     * that stores the starts only places at the end of the presentation.
     */
    static void assertChapterStarts(List<Chapter> expected, List<Chapter> actual) {
        assertEquals(titles(expected), titles(actual), actual.toString());
        for (var index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index).startMicros(), actual.get(index).startMicros(), 1_000, actual.toString());
            if (index < expected.size() - 1) {
                assertEquals(expected.get(index).endMicros(), actual.get(index).endMicros(), 1_000, actual.toString());
            }
        }
    }

    /**
     * Asserts that two chapter lists agree to the millisecond.
     */
    static void assertChapters(List<Chapter> expected, List<Chapter> actual) {
        assertEquals(titles(expected), titles(actual), actual.toString());
        for (var index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index).startMicros(), actual.get(index).startMicros(), 1_000, actual.toString());
            assertEquals(expected.get(index).endMicros(), actual.get(index).endMicros(), 1_000, actual.toString());
        }
    }

    /**
     * A tagged input transcoded into a container.
     *
     * @param input the kind of input
     * @param container the output
     * @param full whether the container stores every tag, the cover and the chapters (AVI stores the first value of
     * the RIFF tags only)
     * @param probeChapters whether ffprobe reads the chapters of the container
     * @param probeCover whether ffprobe exposes the cover as an attached picture
     */
    record Case(Input input, OutputContainer container, boolean full, boolean probeChapters, boolean probeCover) {
        @Override
        public String toString() {
            return input + " -> " + container;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case(Input.MP3, OutputContainer.MP3, true, true, true),
                new Case(Input.MP3, OutputContainer.MATROSKA, true, true, true),
                new Case(Input.MP3, OutputContainer.MP4, true, true, true),
                new Case(Input.MP3, OutputContainer.MOV, true, false, false),
                new Case(Input.FLAC, OutputContainer.FLAC, true, false, true),
                new Case(Input.FLAC, OutputContainer.OGG_FLAC, true, false, false),
                new Case(Input.FLAC, OutputContainer.MATROSKA, true, true, true),
                new Case(Input.OPUS, OutputContainer.OGG, true, true, false),
                new Case(Input.OPUS, OutputContainer.WEBM, true, true, false),
                new Case(Input.ADTS, OutputContainer.M4A, true, true, true),
                new Case(Input.ADTS, OutputContainer.FRAGMENTED_MP4, true, true, false),
                new Case(Input.ADTS, OutputContainer.ADTS, true, false, false),
                new Case(Input.M4A, OutputContainer.MP4, true, true, true),
                new Case(Input.M4A, OutputContainer.MATROSKA, true, true, true),
                new Case(Input.WAV, OutputContainer.WAV, true, false, false),
                new Case(Input.WAV, OutputContainer.AIFF, true, false, false),
                new Case(Input.WAV, OutputContainer.MOV, true, false, false),
                new Case(Input.WAV, OutputContainer.AVI, false, false, false),
                new Case(Input.AC3, OutputContainer.AC3, true, false, false),
                new Case(Input.EAC3, OutputContainer.EAC3, true, false, false),
                new Case(Input.AMR, OutputContainer.AMR, true, false, false));
    }

    @Nested
    @DisplayName("every container that stores tags")
    class Outputs {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.transcode.TranscodeTagsTest#cases")
        @DisplayName("receives the input's tags, cover and chapters by default")
        void copies(Case test) {
            var sample = sample();
            var output = transcode(test.input().tagged(), TranscodeOptions.of(test.container()));
            assertEquals(test.container().format(), Containers.detect(MediaSource.of(output)).orElseThrow());
            var read = TagEditor.read(MediaSource.of(output));
            assertEquals(sample.get(StandardTag.TITLE), read.get(StandardTag.TITLE));
            assertEquals(sample.get(StandardTag.ALBUM), read.get(StandardTag.ALBUM));
            assertEquals(sample.get(StandardTag.GENRE), read.get(StandardTag.GENRE));
            if (test.full()) {
                assertEquals(sample.getAll(StandardTag.ARTIST), read.getAll(StandardTag.ARTIST));
                assertEquals(sample.get(StandardTag.TRACK), read.get(StandardTag.TRACK));
                assertEquals(List.of("calm"), read.getAll("MOOD"));
                assertEquals(1, read.pictures().size(), read.pictures().toString());
                assertArrayEquals(sample.pictures().getFirst().data(), read.pictures().getFirst().data());
                assertEquals(AttachedPicture.Type.FRONT_COVER, read.pictures().getFirst().type());
                assertEquals(titles(CHAPTERS), titles(read.chapters()));
                assertEquals(starts(CHAPTERS), starts(read.chapters()));
            } else {
                assertEquals(sample.get(StandardTag.ARTIST), read.get(StandardTag.ARTIST));
                assertTrue(read.pictures().isEmpty());
                assertTrue(read.chapters().isEmpty());
            }
            var file = save("copy-" + test.input() + "-" + test.container() + "." + test.container().extension(), output);
            Oracle.assume(Oracle.Tool.FFMPEG);
            Oracle.assertDecodesCleanly(file);
            var probe = ffprobe(file);
            var tags = ffprobeTags(probe);
            assertEquals(sample.get(StandardTag.TITLE).orElseThrow(), tags.get("title"), tags.toString());
            if (test.full()) {
                // ffmpeg reads no album (IPRD) from an AVI file
                assertEquals(sample.get(StandardTag.ALBUM).orElseThrow(), tags.get("album"), tags.toString());
            }
            if (test.probeChapters()) {
                assertChapterStarts(CHAPTERS, ffprobeChapters(probe));
            }
            if (test.probeCover()) {
                assertEquals(1, ffprobePictures(probe), probe.toString());
                assertArrayEquals(sample.pictures().getFirst().data(), ffmpegCover(file));
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.transcode.TranscodeTagsTest#cases")
        @DisplayName("receives no tag, cover or chapter with noTags")
        void drops(Case test) {
            var output = transcode(test.input().tagged(), TranscodeOptions.of(test.container()).toBuilder().noTags()
                    .build());
            var read = TagEditor.read(MediaSource.of(output));
            assertTrue(read.get(StandardTag.TITLE).isEmpty(), read.toString());
            assertTrue(read.get(StandardTag.ARTIST).isEmpty(), read.toString());
            assertTrue(read.get("MOOD").isEmpty(), read.toString());
            assertTrue(read.pictures().isEmpty());
            assertTrue(read.chapters().isEmpty());
            var file = save("drop-" + test.input() + "-" + test.container() + "." + test.container().extension(), output);
            var probe = ffprobe(file);
            assertFalse(ffprobeTags(probe).containsKey("title"), probe.toString());
            assertTrue(ffprobeChapters(probe).isEmpty());
            assertEquals(0, ffprobePictures(probe));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.transcode.TranscodeTagsTest#cases")
        @DisplayName("receives the given tags with tags(), and the input's updated with mergeTags()")
        void replacesAndMerges(Case test) {
            var given = MediaTags.builder().set(StandardTag.TITLE, "Given Title").set(StandardTag.COMPOSER, "Composer")
                    .build();
            var replaced = TagEditor.read(MediaSource.of(transcode(test.input().tagged(),
                    TranscodeOptions.of(test.container()).toBuilder().tags(given).build())));
            assertEquals(List.of("Given Title"), replaced.getAll(StandardTag.TITLE));
            assertTrue(replaced.get(StandardTag.ALBUM).isEmpty());
            assertTrue(replaced.pictures().isEmpty());
            assertTrue(replaced.chapters().isEmpty());
            var update = MediaTags.builder().set(StandardTag.TITLE, "Merged Title").set(StandardTag.GENRE, "").build();
            var merged = TagEditor.read(MediaSource.of(transcode(test.input().tagged(),
                    TranscodeOptions.of(test.container()).toBuilder().mergeTags(update).build())));
            assertEquals(List.of("Merged Title"), merged.getAll(StandardTag.TITLE));
            assertEquals(sample().get(StandardTag.ALBUM), merged.get(StandardTag.ALBUM));
            assertTrue(merged.get(StandardTag.GENRE).isEmpty());
            assertEquals(test.full() ? 1 : 0, merged.pictures().size());
            assertEquals(test.full() ? titles(CHAPTERS) : List.of(), titles(merged.chapters()));
        }
    }

    @Test
    @DisplayName("every container stores tags exactly when supportsTags() says so")
    void supportsTags() {
        for (var container : OutputContainer.values()) {
            try (var muxer = container.createMuxer(MediaSink.memory())) {
                if (muxer.supportsTags()) {
                    assertTrue(container.supportsTags(), container.toString());
                }
            } catch (MediaException.Unsupported ignored) {
                // a muxer that needs a seekable sink, checked by the declared track below
            }
        }
        var tagless = Set.of(OutputContainer.IVF, OutputContainer.H264, OutputContainer.HEVC, OutputContainer.VVC,
                OutputContainer.AV1,
                OutputContainer.Y4M, OutputContainer.W64, OutputContainer.LOAS, OutputContainer.ADIF, OutputContainer.MPEG_PS,
                OutputContainer.SRT, OutputContainer.WEBVTT, OutputContainer.ASS,
                OutputContainer.SSA, OutputContainer.TTML, OutputContainer.SAMI, OutputContainer.MICRODVD);
        for (var container : OutputContainer.values()) {
            assertEquals(!tagless.contains(container), container.supportsTags(), container.toString());
        }
    }

    @Nested
    @DisplayName("time ranges")
    class Ranges {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = OutputContainer.class, names = {"MATROSKA", "WAV", "MP4"})
        @DisplayName("cut the chapters to the kept range and move them to its start")
        void chapters(OutputContainer container) {
            var options = TranscodeOptions.of(container).toBuilder().range(500_000, 2_500_000).build();
            var output = transcode(Input.WAV.tagged(), options);
            var expected = List.of(Chapter.of(0, 500_000, "Opening"), Chapter.of(500_000, 1_500_000, "Middle"),
                    Chapter.of(1_500_000, 2_000_000, "Closing"));
            assertChapters(expected, TagEditor.read(MediaSource.of(output)).chapters());
            if (container != OutputContainer.WAV) {
                var probe = ffprobe(save("range-" + container + "." + container.extension(), output));
                assertChapters(expected, ffprobeChapters(probe));
            }
        }

        @Test
        @DisplayName("drop the chapters outside the kept range, with and without seeking")
        void outside() {
            for (var seeking : new boolean[]{true, false}) {
                var output = transcode(Input.WAV.tagged(), TranscodeOptions.of(OutputContainer.MATROSKA).toBuilder()
                        .start(2_200_000).seeking(seeking).build());
                assertEquals(List.of(Chapter.of(0, 800_000, "Closing")), TagEditor.read(MediaSource.of(output)).chapters());
            }
            var output = transcode(Input.WAV.tagged(), TranscodeOptions.of(OutputContainer.MATROSKA).toBuilder()
                    .maxDuration(400_000).build());
            assertEquals(List.of(Chapter.of(0, 400_000, "Opening")), TagEditor.read(MediaSource.of(output)).chapters());
        }
    }

    /**
     * Returns a Matroska file of two PCM tracks, each named, flagged and tagged, with file tags and chapters.
     */
    static byte[] twoTracks() {
        var first = PcmPackets.tone(Codec.PCM_S16LE, 48_000, ChannelLayout.STEREO, 48_000 * 3);
        var second = PcmPackets.tone(Codec.PCM_S16LE, 48_000, ChannelLayout.MONO, 48_000 * 3);
        var secondTrack = second.track().toBuilder().id(2).build();
        var sink = MediaSink.memory();
        try (var muxer = new MatroskaMuxer(sink)) {
            muxer.addTrack(first.track().toBuilder().id(1).build());
            muxer.addTrack(secondTrack);
            muxer.setTags(sample().toBuilder()
                    .set(StandardTag.ENCODER, "Some Muxer")
                    .track(new TrackTags(1, MediaTags.builder().set(StandardTag.TITLE, "Main Mix")
                            .set("BPS", "1536000").set("NUMBER_OF_FRAMES", "100")
                            .set("DURATION", "00:00:03.000000000").set("_STATISTICS_TAGS", "BPS NUMBER_OF_FRAMES")
                            .set(StandardTag.ENCODER, "Track Encoder").set("PRODUCER", "Producer").build(),
                            Set.of(TrackDisposition.DEFAULT)))
                    .track(new TrackTags(2, MediaTags.builder().set(StandardTag.TITLE, "Commentary").build(),
                            Set.of(TrackDisposition.COMMENTARY)))
                    .build());
            var packets = new ArrayList<Packet>();
            for (var packet : first.packets()) {
                packets.add(packet.withTrackId(1));
            }
            for (var packet : second.packets()) {
                packets.add(packet.withTrackId(2));
            }
            packets.sort((a, b) -> Long.compare(a.pts(), b.pts()));
            packets.forEach(muxer::writePacket);
            muxer.finish();
        }
        return sink.toByteArray();
    }

    @Nested
    @DisplayName("track tags")
    class Tracks {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = OutputContainer.class, names = {"MATROSKA", "MP4", "MOV"})
        @DisplayName("follow their tracks to the output, with their dispositions")
        void follow(OutputContainer container) {
            var options = TranscodeOptions.of(container).toBuilder()
                    .audio(AudioTranscodeOptions.builder().allTracks().build()).build();
            var output = transcode(twoTracks(), options);
            var read = TagEditor.read(MediaSource.of(output));
            var ids = trackIds(output);
            assertEquals(2, ids.size());
            assertEquals("Main Mix", read.track(ids.get(0)).orElseThrow().name().orElseThrow());
            assertEquals("Commentary", read.track(ids.get(1)).orElseThrow().name().orElseThrow());
            assertTrue(read.track(ids.get(1)).orElseThrow().has(TrackDisposition.COMMENTARY));
            assertEquals(List.of("Producer"), read.track(ids.get(0)).orElseThrow().tags().getAll("PRODUCER"));
            var probe = ffprobe(save("tracks-" + container + "." + container.extension(), output));
            if (container == OutputContainer.MATROSKA) {
                // ffmpeg reads no track name from an MP4 file
                assertEquals("Main Mix", probe.get("streams.stream.0.tags.title"), probe.toString());
                assertEquals("Commentary", probe.get("streams.stream.1.tags.title"), probe.toString());
            }
            assertEquals("1", probe.get("streams.stream.0.disposition.default"), probe.toString());
            assertEquals("0", probe.get("streams.stream.0.disposition.comment"), probe.toString());
            assertEquals("1", probe.get("streams.stream.1.disposition.comment"), probe.toString());
        }

        @Test
        @DisplayName("of a selected track go to the output track written from it")
        void selected() {
            var options = TranscodeOptions.of(OutputContainer.MATROSKA).toBuilder()
                    .audio(AudioTranscodeOptions.builder().trackId(2).build()).build();
            var output = transcode(twoTracks(), options);
            var read = TagEditor.read(MediaSource.of(output));
            assertEquals(1, read.tracks().size(), read.tracks().toString());
            var track = read.track(trackIds(output).getFirst()).orElseThrow();
            assertEquals("Commentary", track.name().orElseThrow());
            assertTrue(track.has(TrackDisposition.COMMENTARY));
        }

        @Test
        @DisplayName("lose the input's stream statistics, the encoder of a trimmed or encoded stream, and the file its encoder")
        void statistics() {
            var copiedFile = transcode(twoTracks(), TranscodeOptions.of(OutputContainer.MATROSKA));
            var copied = TagEditor.read(MediaSource.of(copiedFile));
            var copiedTrack = copied.track(1).orElseThrow().tags();
            for (var key : List.of("BPS", "NUMBER_OF_FRAMES", "DURATION", "_STATISTICS_TAGS")) {
                assertTrue(copiedTrack.get(key).isEmpty(), copiedTrack.toString());
            }
            assertEquals(List.of("Track Encoder"), copiedTrack.getAll(StandardTag.ENCODER));
            assertEquals(List.of("Producer"), copiedTrack.getAll("PRODUCER"));
            assertEquals(List.of("Some Muxer"), copied.getAll(StandardTag.ENCODER));
            var durationTag = (java.util.function.Predicate<Map<String, String>>) probe -> probe.keySet().stream()
                    .anyMatch(key -> key.equalsIgnoreCase("streams.stream.0.tags.DURATION"));
            assertTrue(durationTag.test(ffprobe(save("statistics-input.mkv", twoTracks()))), "the input's DURATION tag");
            var probe = ffprobe(save("statistics-copy.mkv", copiedFile));
            assertFalse(durationTag.test(probe), probe.toString());
            var trimmed = TagEditor.read(MediaSource.of(transcode(twoTracks(), TranscodeOptions.of(OutputContainer.MATROSKA)
                    .toBuilder().range(0, 2_000_000).build())));
            var trimmedTrack = trimmed.track(1).orElseThrow().tags();
            assertTrue(trimmedTrack.get("BPS").isEmpty(), trimmedTrack.toString());
            assertTrue(trimmedTrack.get(StandardTag.ENCODER).isEmpty(), trimmedTrack.toString());
            assertEquals(List.of("Producer"), trimmedTrack.getAll("PRODUCER"));
            assertEquals(List.of("Some Muxer"), trimmed.getAll(StandardTag.ENCODER));
            var encoded = TagEditor.read(MediaSource.of(transcode(twoTracks(), TranscodeOptions.of(OutputContainer.MATROSKA)
                    .toBuilder().audio(AudioTranscodeOptions.encode(Codec.PCM_S24LE)).build())));
            assertTrue(encoded.get(StandardTag.ENCODER).isEmpty(), encoded.toString());
            assertTrue(encoded.track(1).orElseThrow().tags().get("BPS").isEmpty());
            assertEquals(sample().get(StandardTag.TITLE), encoded.get(StandardTag.TITLE));
        }

        @Test
        @DisplayName("reach a transport stream as names and audio dispositions")
        void transportStream() {
            var input = TagEditor.write(Input.ADTS.untagged(), MediaTags.builder().set(StandardTag.TITLE, "File")
                    .build(), TagWriteOptions.defaults());
            var given = MediaTags.builder().track(new TrackTags(trackIds(input).getFirst(), MediaTags.builder()
                    .set(StandardTag.TITLE, "Narration").build(), Set.of(TrackDisposition.VISUAL_IMPAIRED))).build();
            var output = transcode(input, TranscodeOptions.of(OutputContainer.MPEG_TS).toBuilder().mergeTags(given).build());
            var read = TagEditor.read(MediaSource.of(output));
            var track = read.track(trackIds(output).getFirst()).orElseThrow();
            assertEquals("Narration", track.name().orElseThrow());
            assertTrue(track.has(TrackDisposition.VISUAL_IMPAIRED));
        }
    }

    /**
     * Returns the identifiers of the audio tracks of a file, leaving out a QuickTime chapter track.
     */
    static List<Integer> trackIds(byte[] file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            return demuxer.tracks().stream().filter(track -> track.kind() == TrackKind.AUDIO).map(Track::id).toList();
        }
    }

    @Nested
    @DisplayName("value handling")
    class Values {
        @Test
        @DisplayName("an input value the container rejects is left out of a copy and fails a replacement")
        void rejected() {
            var input = TagEditor.write(Input.ADTS.untagged(), MediaTags.builder().set(StandardTag.TITLE, "Tempo")
                    .set(StandardTag.BPM, "fast").build(), TagWriteOptions.defaults());
            var read = TagEditor.read(MediaSource.of(transcode(input, TranscodeOptions.of(OutputContainer.M4A))));
            assertEquals(List.of("Tempo"), read.getAll(StandardTag.TITLE));
            assertTrue(read.get(StandardTag.BPM).isEmpty());
            var replace = TranscodeOptions.of(OutputContainer.M4A).toBuilder().tags(MediaTags.builder()
                    .set(StandardTag.BPM, "fast").build()).build();
            assertThrows(MediaException.Unsupported.class, () -> transcode(input, replace));
            var merge = TranscodeOptions.of(OutputContainer.M4A).toBuilder().mergeTags(MediaTags.builder()
                    .set(StandardTag.BPM, "128").build()).build();
            assertEquals(List.of("128"), TagEditor.read(MediaSource.of(transcode(input, merge))).getAll(StandardTag.BPM));
        }

        @Test
        @DisplayName("a picture block a copied FLAC configuration embeds does not reach an Ogg file beside the tags")
        void embeddedPicture() {
            var source = com.github.auties00.smalt.container.MuxSupport.demux(Input.FLAC.untagged());
            var track = (com.github.auties00.smalt.track.AudioTrack) source.tracks().getFirst();
            var config = track.codecConfig().orElseThrow().clone();
            // clear the last-block flag of the final block and append a PICTURE block holding other bytes
            for (var offset = 4; offset + 4 <= config.length; ) {
                var next = offset + 4 + ((config[offset + 1] & 0xFF) << 16 | (config[offset + 2] & 0xFF) << 8
                        | config[offset + 3] & 0xFF);
                if (next >= config.length) {
                    config[offset] &= 0x7F;
                }
                offset = next;
            }
            var mime = "image/png".getBytes(StandardCharsets.US_ASCII);
            var data = new byte[]{9, 8, 7, 6};
            var body = java.nio.ByteBuffer.allocate(32 + mime.length + data.length).putInt(3).putInt(mime.length)
                    .put(mime).putInt(0).putInt(1).putInt(1).putInt(24).putInt(0).putInt(data.length).put(data).array();
            var block = java.nio.ByteBuffer.allocate(4 + body.length).putInt(0x86 << 24 | body.length).put(body).array();
            var embedded = new byte[config.length + block.length];
            System.arraycopy(config, 0, embedded, 0, config.length);
            System.arraycopy(block, 0, embedded, config.length, block.length);
            var sink = MediaSink.memory();
            try (var muxer = new MatroskaMuxer(sink)) {
                muxer.addTrack(track.toBuilder().codecConfig(embedded).build());
                muxer.setTags(sample());
                source.order().forEach(muxer::writePacket);
                muxer.finish();
            }
            var input = sink.toByteArray();
            var copied = TagEditor.read(MediaSource.of(transcode(input, TranscodeOptions.of(OutputContainer.OGG_FLAC))));
            assertEquals(1, copied.pictures().size(), copied.pictures().toString());
            assertArrayEquals(cover(), copied.pictures().getFirst().data());
            var dropped = TagEditor.read(MediaSource.of(transcode(input, TranscodeOptions.of(OutputContainer.OGG_FLAC)
                    .toBuilder().noTags().build())));
            assertTrue(dropped.pictures().isEmpty(), dropped.pictures().toString());
        }

        @Test
        @DisplayName("a source read once keeps its tags, read from the demuxer")
        void forwardOnly() {
            var input = Input.MP3.tagged();
            var sink = MediaSink.memory();
            Transcoder.transcode(MediaSource.of(new ByteArrayInputStream(input), input.length), sink,
                    TranscodeOptions.of(OutputContainer.MATROSKA));
            var read = TagEditor.read(MediaSource.of(sink.toByteArray()));
            assertEquals(sample().get(StandardTag.TITLE), read.get(StandardTag.TITLE));
            assertEquals(sample().get(StandardTag.ALBUM), read.get(StandardTag.ALBUM));
            assertEquals(1, read.pictures().size());
            assertArrayEquals(cover(), read.pictures().getFirst().data());
            assertEquals(titles(CHAPTERS), titles(read.chapters()));
        }

        @Test
        @DisplayName("the options carry the mode and the tags")
        void options() {
            var defaults = TranscodeOptions.defaults();
            assertEquals(TagMode.COPY, defaults.tagMode());
            assertTrue(defaults.tags().isEmpty());
            var given = MediaTags.builder().set(StandardTag.TITLE, "x").build();
            var replace = defaults.toBuilder().tags(given).build();
            assertEquals(TagMode.REPLACE, replace.tagMode());
            assertEquals(given, replace.tags().orElseThrow());
            assertEquals(replace, replace.toBuilder().build());
            var merge = defaults.toBuilder().mergeTags(given).build();
            assertEquals(TagMode.MERGE, merge.tagMode());
            assertFalse(merge.equals(replace));
            assertEquals(TagMode.DROP, replace.toBuilder().noTags().build().tagMode());
            assertTrue(replace.toBuilder().noTags().build().tags().isEmpty());
            assertEquals(defaults, replace.toBuilder().copyTags().build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().tags(null));
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().mergeTags(null));
            var remux = RemuxOptions.defaults();
            assertEquals(TagMode.COPY, remux.tagMode());
            assertEquals(TagMode.MERGE, remux.toBuilder().mergeTags(given).build().tagMode());
            assertEquals(remux, remux.toBuilder().tags(given).copyTags().build());
            assertEquals(TagMode.DROP, PackagingOptions.hls().toBuilder().noTags().build().tagMode());
            assertEquals(PackagingOptions.hls(), PackagingOptions.hls().toBuilder().tags(given).copyTags().build());
        }
    }

    @Nested
    @DisplayName("MP4 remuxing")
    class Remux {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Input.class, names = {"M4A", "OPUS", "FLAC"})
        @DisplayName("keeps the tags, cover and chapters of ISO base media and other inputs")
        void remux(Input kind) {
            var input = kind == Input.M4A ? kind.tagged() : transcode(kind.tagged(), TranscodeOptions.of(
                    OutputContainer.MATROSKA));
            var sink = MediaSink.memory();
            Mp4Remuxer.remux(MediaSource.of(input), sink, RemuxOptions.audio());
            var output = sink.toByteArray();
            var read = TagEditor.read(MediaSource.of(output));
            assertEquals(sample().get(StandardTag.TITLE), read.get(StandardTag.TITLE));
            assertEquals(sample().getAll(StandardTag.ARTIST), read.getAll(StandardTag.ARTIST));
            assertEquals(List.of("calm"), read.getAll("MOOD"));
            assertArrayEquals(cover(), read.pictures().getFirst().data());
            assertEquals(titles(CHAPTERS), titles(read.chapters()));
            var file = save("remux-" + kind + ".m4a", output);
            Oracle.assume(Oracle.Tool.FFMPEG);
            Oracle.assertDecodesCleanly(file);
            var probe = ffprobe(file);
            assertEquals(sample().get(StandardTag.TITLE).orElseThrow(), ffprobeTags(probe).get("title"));
            assertChapters(CHAPTERS, ffprobeChapters(probe));
            assertArrayEquals(cover(), ffmpegCover(file));
        }

        @Test
        @DisplayName("follows the tag mode and maps the track tags of the copied tracks")
        void modes() {
            var input = twoTracks();
            var dropped = MediaSink.memory();
            Mp4Remuxer.remux(MediaSource.of(input), dropped, RemuxOptions.builder().noTags().build());
            var none = TagEditor.read(MediaSource.of(dropped.toByteArray()));
            assertTrue(none.get(StandardTag.TITLE).isEmpty());
            assertTrue(none.pictures().isEmpty());
            assertTrue(none.chapters().isEmpty());
            var selected = MediaSink.memory();
            Mp4Remuxer.remux(MediaSource.of(input), selected, RemuxOptions.builder().audioTrack(2)
                    .mergeTags(MediaTags.builder().set(StandardTag.ALBUM, "Remuxed").build()).build());
            var read = TagEditor.read(MediaSource.of(selected.toByteArray()));
            assertEquals(List.of("Remuxed"), read.getAll(StandardTag.ALBUM));
            assertEquals(sample().get(StandardTag.TITLE), read.get(StandardTag.TITLE));
            var ids = trackIds(selected.toByteArray());
            assertEquals("Commentary", read.track(ids.getFirst()).orElseThrow().name().orElseThrow());
        }

        @Test
        @DisplayName("is the path a whole-file copy into MP4 takes, with the tags")
        void transcoderPath() {
            var result = Transcoder.transcode(MediaSource.of(Input.M4A.tagged()), MediaSink.memory(),
                    TranscodeOptions.remux(OutputContainer.M4A));
            assertTrue(result.remuxed());
            var output = transcode(Input.M4A.tagged(), TranscodeOptions.remux(OutputContainer.M4A).toBuilder()
                    .mergeTags(MediaTags.builder().set(StandardTag.TITLE, "Remuxed Title").build()).build());
            var read = TagEditor.read(MediaSource.of(output));
            assertEquals(List.of("Remuxed Title"), read.getAll(StandardTag.TITLE));
            assertEquals(sample().get(StandardTag.ALBUM), read.get(StandardTag.ALBUM));
            assertEquals(1, read.pictures().size());
        }
    }

    @Nested
    @DisplayName("packaging")
    class Packaging {
        @Test
        @DisplayName("writes the rendition's tags into CMAF initialization segments")
        void cmaf() throws IOException {
            var directory = Files.createDirectories(DIRECTORY.resolve("package-cmaf"));
            Packager.pack(List.of(Rendition.of(MediaSource.of(Input.M4A.tagged()))), PackageOutput.directory(directory),
                    PackagingOptions.dash());
            var init = Files.readAllBytes(directory.resolve("r0_audio_init.mp4"));
            var read = TagEditor.read(MediaSource.of(init));
            assertEquals(sample().get(StandardTag.TITLE), read.get(StandardTag.TITLE));
            assertArrayEquals(cover(), read.pictures().getFirst().data());
            var dropped = Files.createDirectories(DIRECTORY.resolve("package-cmaf-dropped"));
            Packager.pack(List.of(Rendition.of(MediaSource.of(Input.M4A.tagged()))), PackageOutput.directory(dropped),
                    PackagingOptions.dash().toBuilder().noTags().build());
            var none = TagEditor.read(MediaSource.of(Files.readAllBytes(dropped.resolve("r0_audio_init.mp4"))));
            assertTrue(none.get(StandardTag.TITLE).isEmpty());
            assertTrue(none.pictures().isEmpty());
        }

        @Test
        @DisplayName("writes the track names into transport stream segments")
        void transportStream() throws IOException {
            var input = namedAac();
            var directory = Files.createDirectories(DIRECTORY.resolve("package-ts"));
            Packager.pack(List.of(Rendition.of(MediaSource.of(input))), PackageOutput.directory(directory),
                    PackagingOptions.hls());
            var segment = Files.readAllBytes(directory.resolve("r0_00001.ts"));
            var read = TagEditor.read(MediaSource.of(segment));
            var ids = trackIds(segment);
            assertEquals("Narration", read.track(ids.getFirst()).orElseThrow().name().orElseThrow());
        }
    }

    /**
     * Returns an MP4 file holding one AAC track named "Narration".
     */
    static byte[] namedAac() {
        var untagged = Input.M4A.untagged();
        return TagEditor.write(untagged, MediaTags.builder().track(TrackTags.of(trackIds(untagged).getFirst(),
                "Narration")).build(), TagWriteOptions.defaults());
    }

    /**
     * The chapters of the ffmetadata file of the ffmpeg-tagged inputs.
     */
    private static final List<Chapter> ORACLE_CHAPTERS = List.of(Chapter.of(0, 1_200_000, "First"),
            Chapter.of(1_200_000, 3_000_000, "Second"));

    static Stream<Object[]> conversions() {
        return Stream.of(
                new Object[]{"mp3", List.of("-c:a", "libmp3lame", "-id3v2_version", "4"), OutputContainer.MATROSKA},
                new Object[]{"mp3", List.of("-c:a", "libmp3lame", "-id3v2_version", "3"), OutputContainer.M4A},
                new Object[]{"m4a", List.of("-c:a", "aac"), OutputContainer.MOV},
                new Object[]{"m4a", List.of("-c:a", "aac"), OutputContainer.MATROSKA},
                new Object[]{"flac", List.of("-c:a", "flac"), OutputContainer.MATROSKA},
                new Object[]{"flac", List.of("-c:a", "flac"), OutputContainer.MP4},
                new Object[]{"mkv", List.of("-c:a", "aac"), OutputContainer.MP4},
                new Object[]{"mkv", List.of("-c:a", "libopus"), OutputContainer.M4A});
    }

    @Nested
    @DisplayName("ffmpeg-tagged inputs")
    class FfmpegInputs {
        static Path metadata() {
            return Scratch.share(DIRECTORY.resolve("ffmetadata.txt"), (";FFMETADATA1\ntitle=Oracle Title\n"
                    + "artist=Oracle Artist\nalbum=Oracle Album\ngenre=Rock\ntrack=4/10\ncomment=oracle comment\n"
                    + "\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=1200\ntitle=First\n"
                    + "\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=1200\nEND=3000\ntitle=Second\n")
                    .getBytes(StandardCharsets.UTF_8));
        }

        static Path tagged(String extension, List<String> codec) {
            var cover = Fixtures.testImage("png", 32, 24, List.of());
            // Matroska stores the cover as an attachment, which ffmpeg exposes as an attached picture when reading
            var output = new ArrayList<>(extension.equals("mkv")
                    ? List.of("-map", "0:a", "-map_metadata", "1", "-map_chapters", "1", "-attach", cover.toString(),
                    "-metadata:s:t", "mimetype=image/png", "-metadata:s:t", "filename=cover.png")
                    : List.of("-map", "0:a", "-map", "2:v", "-c:v", "copy", "-disposition:v", "attached_pic",
                    "-map_metadata", "1", "-map_chapters", "1"));
            output.addAll(codec);
            return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=3",
                    "-i", metadata().toString(), "-i", cover.toString()), output);
        }

        @ParameterizedTest(name = "{0} -> {2}")
        @MethodSource("com.github.auties00.smalt.transcode.TranscodeTagsTest#conversions")
        @DisplayName("keep the tags, chapters and cover ffprobe reports")
        void agree(String extension, List<String> codec, OutputContainer container) {
            var input = tagged(extension, codec);
            var output = save("oracle-" + extension + "-" + Math.abs(String.join("", codec).hashCode()) + "."
                    + container.extension(), transcode(Fixtures.read(input), TranscodeOptions.of(container)));
            var expected = new HashMap<>(Map.of("title", "Oracle Title", "artist", "Oracle Artist", "album",
                    "Oracle Album", "genre", "Rock", "comment", "oracle comment"));
            if (extension.equals("flac")) {
                // ffmpeg writes its comment as a Vorbis DESCRIPTION, which is a tag of its own in Smalt's vocabulary
                expected.remove("comment");
            }
            var beforeTags = ffprobeTags(ffprobe(input));
            var after = ffprobe(output);
            var afterTags = ffprobeTags(after);
            for (var entry : expected.entrySet()) {
                assertEquals(entry.getValue(), beforeTags.get(entry.getKey()), entry.getKey() + ": " + beforeTags);
                assertEquals(entry.getValue(), afterTags.get(entry.getKey()), entry.getKey() + ": " + afterTags);
            }
            // ffmpeg writes no chapters into a FLAC file
            var chapters = extension.equals("flac") ? List.<Chapter>of() : ORACLE_CHAPTERS;
            assertChapters(chapters, ffprobeChapters(after));
            if (container.format() == com.github.auties00.smalt.container.ContainerFormat.MP4) {
                // Smalt reads the end of the last MP4 chapter from the Nero chpl box, which stores the starts only
                assertChapterStarts(chapters, TagEditor.read(output).chapters());
            } else {
                assertChapters(chapters, TagEditor.read(output).chapters());
            }
            assertEquals(1, ffprobePictures(after), after.toString());
            assertArrayEquals(ffmpegCover(input), ffmpegCover(output));
            Oracle.assertDecodesCleanly(output);
        }
    }

    @Test
    @DisplayName("the default file conversion keeps the tags")
    void files() throws IOException {
        var input = save("files-input.mp3", Input.MP3.tagged());
        var output = DIRECTORY.resolve("files-output.mka");
        Files.deleteIfExists(output);
        Transcoder.transcode(input, output);
        var read = TagEditor.read(output);
        assertEquals(sample().get(StandardTag.TITLE), read.get(StandardTag.TITLE));
        assertTrue(Arrays.equals(cover(), read.pictures().getFirst().data()));
    }
}
