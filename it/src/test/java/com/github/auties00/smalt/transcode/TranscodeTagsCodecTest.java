package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Re-encodes tagged inputs with the codecs of the other artifacts (MP3 to AAC in M4A, FLAC to Opus in Ogg, M4A to
 * MP3, a Matroska video to VP9 and Opus in WebM, trimmed) and checks with {@link TagEditor} and ffprobe that the
 * tags, the cover and the chapters (cut to the kept range) reach the output, while the encoder and ReplayGain tags
 * that described the input's streams do not.
 */
@DisplayName("Tags through re-encoding transcodes")
class TranscodeTagsCodecTest {
    private static final Path DIRECTORY = Scratch.directory("transcode-tags-codecs");

    private static final List<Chapter> CHAPTERS = List.of(Chapter.of(0, 1_000_000, "Opening"),
            Chapter.of(1_000_000, 2_000_000, "Middle"), Chapter.of(2_000_000, 3_000_000, "Closing"));

    private static byte[] cover() {
        return Fixtures.read(Fixtures.testImage("png", 32, 24, List.of()));
    }

    private static MediaTags sample() {
        return MediaTags.builder()
                .set(StandardTag.TITLE, "Encoded Title")
                .set(StandardTag.ARTIST, "Encoded Artist")
                .set(StandardTag.ALBUM, "Encoded Album")
                .set(StandardTag.ENCODER, "Input Encoder")
                .set(StandardTag.REPLAYGAIN_TRACK_GAIN, "-4.00 dB")
                .addPicture(AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/png", "", cover()))
                .chapters(CHAPTERS)
                .build();
    }

    private static byte[] tagged(String extension, List<String> input, List<String> codec) {
        var output = new java.util.ArrayList<>(List.of("-map_metadata", "-1"));
        output.addAll(codec);
        return TagEditor.write(Fixtures.read(Fixtures.ffmpeg(extension, input, output)), sample(),
                TagWriteOptions.defaults());
    }

    private static final List<String> TONE = List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=3");

    static Stream<Arguments> conversions() {
        return Stream.of(
                Arguments.of("mp3 to aac in m4a", "mp3", TONE, List.of("-c:a", "libmp3lame"), OutputContainer.M4A,
                        Codec.AAC),
                Arguments.of("flac to opus in ogg", "flac", TONE, List.of("-c:a", "flac"), OutputContainer.OGG,
                        Codec.OPUS),
                Arguments.of("m4a to mp3", "m4a", TONE, List.of("-c:a", "aac"), OutputContainer.MP3, Codec.MP3),
                Arguments.of("flac to aac in mp4", "flac", TONE, List.of("-c:a", "flac"), OutputContainer.MP4, Codec.AAC));
    }

    private static Map<String, String> ffprobe(Path file) {
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-show_format", "-show_streams", "-show_chapters", "-of", "flat", file.toString())), StandardCharsets.UTF_8);
        var result = new HashMap<String, String>();
        for (var line : output.split("\\R")) {
            var separator = line.indexOf('=');
            if (separator > 0) {
                var value = line.substring(separator + 1);
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                result.put(line.substring(0, separator).toLowerCase(Locale.ROOT), value);
            }
        }
        return result;
    }

    private static String probeTag(Map<String, String> probe, String key) {
        var value = probe.get("format.tags." + key);
        return value != null ? value : probe.get("streams.stream.0.tags." + key);
    }

    private static Path save(String name, byte[] data) throws IOException {
        var file = DIRECTORY.resolve(name);
        Files.write(file, data);
        return file;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversions")
    @DisplayName("keeps the tags, cover and chapters and drops the encoder and ReplayGain of the input")
    void encodes(String name, String extension, List<String> input, List<String> codec, OutputContainer container,
                 Codec target) throws IOException {
        var sink = MediaSink.memory();
        var options = TranscodeOptions.of(container).toBuilder()
                .audio(AudioTranscodeOptions.builder().codec(target).gainDb(-1).build()).build();
        var result = Transcoder.transcode(MediaSource.of(tagged(extension, input, codec)), sink, options);
        assertEquals(target, result.streams().getFirst().track().codec());
        var output = sink.toByteArray();
        var read = TagEditor.read(MediaSource.of(output));
        assertEquals(List.of("Encoded Title"), read.getAll(StandardTag.TITLE));
        assertEquals(List.of("Encoded Artist"), read.getAll(StandardTag.ARTIST));
        assertEquals(List.of("Encoded Album"), read.getAll(StandardTag.ALBUM));
        assertTrue(read.get(StandardTag.REPLAYGAIN_TRACK_GAIN).isEmpty(), read.toString());
        assertTrue(read.get(StandardTag.ENCODER).filter("Input Encoder"::equals).isEmpty(), read.toString());
        assertArrayEquals(cover(), read.pictures().getFirst().data());
        assertEquals(CHAPTERS.stream().map(Chapter::title).toList(), read.chapters().stream().map(Chapter::title).toList());
        Oracle.assume(Oracle.Tool.FFPROBE);
        var file = save(name.replace(' ', '-') + "." + container.extension(), output);
        Oracle.assertDecodesCleanly(file);
        var probe = ffprobe(file);
        assertEquals("Encoded Title", probeTag(probe, "title"), probe.toString());
        assertEquals("Encoded Album", probeTag(probe, "album"), probe.toString());
    }

    @Test
    @DisplayName("cuts the chapters of a trimmed video transcode and keeps the cover")
    void video() throws IOException {
        var input = tagged("mkv", List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=25:duration=3", "-f", "lavfi",
                "-i", "sine=frequency=440:sample_rate=48000:duration=3"), List.of("-c:v", "libx264", "-preset",
                "ultrafast", "-c:a", "aac", "-shortest"));
        var sink = MediaSink.memory();
        var options = TranscodeOptions.of(OutputContainer.WEBM).toBuilder().range(1_500_000, 2_800_000).build();
        Transcoder.transcode(MediaSource.of(input), sink, options);
        var output = sink.toByteArray();
        var read = TagEditor.read(MediaSource.of(output));
        assertEquals(List.of("Encoded Title"), read.getAll(StandardTag.TITLE));
        assertEquals(List.of(Chapter.of(0, 500_000, "Middle"), Chapter.of(500_000, 1_300_000, "Closing")),
                read.chapters());
        assertArrayEquals(cover(), read.pictures().getFirst().data());
        Oracle.assume(Oracle.Tool.FFPROBE);
        var file = save("trimmed.webm", output);
        Oracle.assertDecodesCleanly(file);
        var probe = ffprobe(file);
        assertEquals("Middle", probe.get("chapters.chapter.0.tags.title"), probe.toString());
        assertEquals(0.5, Double.parseDouble(probe.get("chapters.chapter.0.end_time")), 0.001, probe.toString());
        assertEquals("Closing", probe.get("chapters.chapter.1.tags.title"), probe.toString());
    }
}
