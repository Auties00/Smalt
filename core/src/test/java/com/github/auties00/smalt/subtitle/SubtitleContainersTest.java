package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.matroska.MatroskaDocType;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.probe.DataStreamInfo;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks subtitle tracks through every container that carries them: SubRip and WebVTT files,
 * Matroska and WebM ({@code S_TEXT/UTF8}, {@code S_TEXT/ASS}, {@code S_TEXT/WEBVTT},
 * {@code D_WEBVTT}) and MP4 ({@code tx3g}, {@code wvtt}). Files are written by the muxers and read
 * back by the demuxers, read by ffprobe and ffmpeg, and files written by ffmpeg are read by Smalt.
 */
@DisplayName("Subtitle containers")
class SubtitleContainersTest {
    private static final List<SubtitleCue> CUES = List.of(
            SubtitleCue.of(500_000, 2_000_000, "<i>Hello</i> world"),
            SubtitleCue.of(1_500_000, 3_000_000, "Overlap \u00E9\nsecond line"),
            SubtitleCue.of(5_000_000, 6_250_000, "<b>Bold</b> & <u>under</u>"),
            SubtitleCue.of(9_000_000, 10_000_000, "\u3053\u3093\u306B\u3061\u306F"));

    private static final List<SubtitleCue> SEQUENTIAL = List.of(CUES.get(0), CUES.get(1).withTiming(2_000_000, 3_000_000),
            CUES.get(2), CUES.get(3));

    private static byte[] write(Function<MediaSink, Muxer> factory, Codec codec, List<SubtitleCue> cues, SubtitleMarkup markup) {
        var sink = MediaSink.memory();
        var track = SubtitleEncoder.track(codec, 1);
        var encoder = new SubtitleEncoder(track);
        try (var muxer = factory.apply(sink)) {
            muxer.addTrack(track);
            for (var cue : cues) {
                muxer.writePacket(encoder.encode(cue, markup));
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    private static List<SubtitleCue> read(byte[] file, SubtitleMarkup markup) {
        return Subtitles.read(MediaSource.of(file), SubtitleReaderOptions.builder().markup(markup).build());
    }

    private static Path save(Path directory, String name, byte[] data) throws IOException {
        var path = directory.resolve(name);
        Files.write(path, data);
        return path;
    }

    private static String ffmpegToSrt(Path input) {
        return new String(Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-i", input.toString(),
                "-map", "0:s:0", "-f", "srt", "-")), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Nested
    @DisplayName("text files")
    class SubtitleFiles {
        @Test
        @DisplayName("recognizes SubRip and WebVTT files and reads them through the registry")
        void detect() {
            var srt = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,000\r\nx\r\n".getBytes(StandardCharsets.UTF_8);
            var vtt = "WEBVTT\n\n00:01.000 --> 00:02.000\nx\n".getBytes(StandardCharsets.UTF_8);
            assertEquals(ContainerFormat.SUBRIP, Containers.detect(MediaSource.of(srt)).orElseThrow());
            assertEquals(ContainerFormat.WEBVTT, Containers.detect(MediaSource.of(vtt)).orElseThrow());
            assertTrue(Containers.detect(MediaSource.of("plain text\nnothing\n".getBytes(StandardCharsets.UTF_8)))
                    .isEmpty());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(srt))) {
                assertEquals(Codec.SUBRIP, demuxer.tracks().getFirst().codec());
                assertEquals(2_000_000, demuxer.durationMicros());
                assertTrue(demuxer.isSeekable());
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ContainerFormat.class, names = {"SUBRIP", "WEBVTT"})
        @DisplayName("round-trip every cue")
        void roundTrip(ContainerFormat format) {
            var sink = MediaSink.memory();
            Subtitles.write(CUES, SubtitleMarkup.SUBRIP, format, sink);
            assertEquals(CUES, read(sink.toByteArray(), SubtitleMarkup.SUBRIP));
        }

        @Test
        @DisplayName("keep WebVTT identifiers, settings and headers")
        void webVttDetails() {
            var text = "WEBVTT\n\nSTYLE\n::cue { color: lime }\n\nfirst\n00:00:01.000 --> 00:00:02.000 line:0 align:end\n"
                    + "a &lt; b\n";
            var demuxer = Containers.openDemuxer(MediaSource.of(text.getBytes(StandardCharsets.UTF_8)));
            var track = demuxer.tracks().getFirst();
            assertEquals("WEBVTT\n\nSTYLE\n::cue { color: lime }", new String(track.codecConfig().orElseThrow(), StandardCharsets.UTF_8));
            var sink = MediaSink.memory();
            try (var muxer = Containers.openMuxer(ContainerFormat.WEBVTT, sink)) {
                muxer.addTrack(track);
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            assertEquals(text + "\n", new String(sink.toByteArray(), StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("seek to the cues still shown")
        void seek() {
            var sink = MediaSink.memory();
            Subtitles.write(CUES, SubtitleMarkup.SUBRIP, ContainerFormat.SUBRIP, sink);
            try (var reader = SubtitleReader.open(MediaSource.of(sink.toByteArray()))) {
                reader.seek(1_800_000);
                assertEquals(CUES.get(0), reader.read());
                reader.seek(4_000_000);
                assertEquals(CUES.get(2), reader.read());
                reader.seek(20_000_000);
                assertEquals(null, reader.read());
            }
        }

        @Test
        @DisplayName("are read by ffmpeg")
        void ffmpeg(@TempDir Path directory) throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            for (var format : List.of(ContainerFormat.SUBRIP, ContainerFormat.WEBVTT)) {
                var sink = MediaSink.memory();
                Subtitles.write(CUES, SubtitleMarkup.SUBRIP, format, sink);
                var path = save(directory, format == ContainerFormat.SUBRIP ? "a.srt" : "a.vtt", sink.toByteArray());
                var converted = ffmpegToSrt(path);
                assertEquals(CUES, Subtitles.read(MediaSource.of(converted.getBytes(StandardCharsets.UTF_8))), format.name());
            }
        }
    }

    @Nested
    @DisplayName("Matroska")
    class Matroska {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"SUBRIP", "WEBVTT", "ASS"})
        @DisplayName("round-trips subtitle tracks and passes ffmpeg")
        void roundTrip(Codec codec, @TempDir Path directory) throws IOException {
            var file = write(MatroskaMuxer::new, codec, CUES, SubtitleMarkup.SUBRIP);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                assertEquals(codec, demuxer.tracks().getFirst().codec());
            }
            assertEquals(CUES, read(file, SubtitleMarkup.SUBRIP));
            if (Oracle.find(Oracle.Tool.FFPROBE).isPresent()) {
                var path = save(directory, "a.mkv", file);
                var probe = Oracle.probe(path);
                assertTrue(probe.contains("\"codec_type\": \"subtitle\""), probe);
                assertEquals(CUES, Subtitles.read(MediaSource.of(ffmpegToSrt(path).getBytes(StandardCharsets.UTF_8))));
            }
        }

        @Test
        @DisplayName("writes WebVTT tracks as D_WEBVTT blocks in WebM and Matroska")
        void webm(@TempDir Path directory) throws IOException {
            var cue = new SubtitleCue(1_000_000, 2_000_000, "payload", "the-id", "align:start");
            var file = write(sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.webm()), Codec.WEBVTT, List.of(cue), SubtitleMarkup.WEBVTT);
            assertTrue(new String(file, StandardCharsets.ISO_8859_1).contains("D_WEBVTT/SUBTITLES"));
            assertEquals(List.of(cue), read(file, SubtitleMarkup.WEBVTT));
            var matroska = write(MatroskaMuxer::new, Codec.WEBVTT, List.of(cue), SubtitleMarkup.WEBVTT);
            assertEquals(List.of(cue), read(matroska, SubtitleMarkup.WEBVTT));
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                assertEquals("1\n00:00:01,000 --> 00:00:02,000\npayload\n\n", ffmpegToSrt(save(directory, "a.webm", file)));
            }
        }

        @Test
        @DisplayName("refuses SubRip in WebM")
        void webmCodecs() {
            assertTrue(MatroskaMuxer.supports(MatroskaDocType.WEBM, Codec.WEBVTT));
            assertTrue(!MatroskaMuxer.supports(MatroskaDocType.WEBM, Codec.SUBRIP));
            assertThrows(IllegalArgumentException.class, () -> write(sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.webm()),
                    Codec.SUBRIP, CUES, SubtitleMarkup.SUBRIP));
        }

        @Test
        @DisplayName("passes ASS events and the script header through unchanged")
        void assPassThrough() {
            var header = "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\nFormat: Name\nStyle: Fancy\n\n[Events]\nFormat: Layer\n";
            var track = DataTrack.builder().id(1).codec(Codec.ASS).timeBase(com.github.auties00.smalt.track.Rational.MILLISECONDS)
                    .codecConfig(header.getBytes(StandardCharsets.UTF_8)).build();
            var event = "0,0,Fancy,Actor,0,0,0,,{\\pos(10,20)}Text";
            var sink = MediaSink.memory();
            try (var muxer = new MatroskaMuxer(sink)) {
                muxer.addTrack(track);
                muxer.writePacket(new com.github.auties00.smalt.track.Packet(1, event.getBytes(StandardCharsets.UTF_8), 1000, 1000, 500, true));
                muxer.finish();
            }
            try (var demuxer = Containers.openDemuxer(MediaSource.of(sink.toByteArray()))) {
                var read = demuxer.tracks().getFirst();
                assertEquals(header, new String(read.codecConfig().orElseThrow(), StandardCharsets.UTF_8));
                var packet = demuxer.read();
                assertEquals(event, new String(packet.data(), StandardCharsets.UTF_8));
                assertEquals(500, packet.duration());
                var cue = new SubtitleDecoder(read).decode(packet);
                assertEquals("{\\pos(10,20)}Text", cue.text());
                assertEquals("Text", Subtitles.plainText(cue.text(), SubtitleMarkup.ASS));
            }
        }
    }

    @Nested
    @DisplayName("MP4")
    class Mp4 {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"MOV_TEXT", "WEBVTT"})
        @DisplayName("cuts overlapping cues into samples, reads them back joined and passes ffmpeg")
        void roundTrip(Codec codec, @TempDir Path directory) throws IOException {
            var file = write(Mp4Muxer::new, codec, CUES, SubtitleMarkup.SUBRIP);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                assertEquals(codec, demuxer.tracks().getFirst().codec());
            }
            var cues = read(file, SubtitleMarkup.SUBRIP);
            if (codec == Codec.WEBVTT) {
                // each sample lists every cue shown, so the pieces of the cut cues join back
                assertEquals(CUES, cues);
            } else {
                // a 3GPP sample shows one text, so the overlap becomes a cue holding both
                var first = CUES.get(0);
                var second = CUES.get(1);
                assertEquals(List.of(first.withTiming(500_000, 1_500_000), SubtitleCue.of(1_500_000, 2_000_000, first.text() + "\n"
                        + second.text()), second.withTiming(2_000_000, 3_000_000), CUES.get(2), CUES.get(3)), cues);
            }
            if (Oracle.find(Oracle.Tool.FFPROBE).isPresent()) {
                var path = save(directory, "a.mp4", file);
                var probe = Oracle.probe(path);
                assertTrue(probe.contains(codec == Codec.MOV_TEXT ? "\"codec_name\": \"mov_text\"" : "\"codec_tag_string\": \"wvtt\""), probe);
                if (codec == Codec.MOV_TEXT) {
                    var srt = ffmpegToSrt(path);
                    assertTrue(srt.contains("<i>Hello</i> world"), srt);
                    assertTrue(srt.contains("Overlap \u00E9\nsecond line"), srt);
                }
            }
        }

        @Test
        @DisplayName("refuses subtitles in the M4A brand")
        void m4a() {
            assertThrows(IllegalArgumentException.class, () -> write(sink -> new Mp4Muxer(sink,
                    com.github.auties00.smalt.container.mp4.Mp4MuxerOptions.m4a()), Codec.MOV_TEXT, CUES, SubtitleMarkup.SUBRIP));
        }
    }

    @Nested
    @DisplayName("files written by ffmpeg")
    class FromFfmpeg {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Target.class)
        @DisplayName("are read with every cue")
        void read(Target target, @TempDir Path directory) throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var sink = MediaSink.memory();
            Subtitles.write(SEQUENTIAL, SubtitleMarkup.SUBRIP, ContainerFormat.SUBRIP, sink);
            var srt = save(directory, "in.srt", sink.toByteArray());
            var output = directory.resolve("out." + target.extension);
            var arguments = new ArrayList<>(List.of("-v", "error", "-i", srt.toString()));
            arguments.addAll(target.arguments);
            arguments.add(output.toString());
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), arguments);
            var file = Files.readAllBytes(output);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                assertEquals(target.codec, demuxer.tracks().getFirst().codec());
            }
            var cues = SubtitleContainersTest.read(file, SubtitleMarkup.SUBRIP);
            assertEquals(SEQUENTIAL.size(), cues.size());
            for (var index = 0; index < SEQUENTIAL.size(); index++) {
                assertEquals(SEQUENTIAL.get(index).startMicros(), cues.get(index).startMicros(), 1000);
                assertEquals(SEQUENTIAL.get(index).endMicros(), cues.get(index).endMicros(), 1000);
                assertEquals(Subtitles.plainText(SEQUENTIAL.get(index).text(), SubtitleMarkup.SUBRIP),
                        Subtitles.plainText(cues.get(index).text(), SubtitleMarkup.SUBRIP));
            }
        }

        enum Target {
            MKV_SRT("mkv", Codec.SUBRIP, List.of("-c:s", "srt")),
            MKV_ASS("mkv", Codec.ASS, List.of("-c:s", "ass")),
            MKV_WEBVTT("mkv", Codec.WEBVTT, List.of("-c:s", "webvtt")),
            WEBM("webm", Codec.WEBVTT, List.of("-c:s", "webvtt")),
            MP4_MOV_TEXT("mp4", Codec.MOV_TEXT, List.of("-c:s", "mov_text")),
            VTT("vtt", Codec.WEBVTT, List.of());

            private final String extension;
            private final Codec codec;
            private final List<String> arguments;

            Target(String extension, Codec codec, List<String> arguments) {
                this.extension = extension;
                this.codec = codec;
                this.arguments = arguments;
            }
        }
    }

    @Nested
    @DisplayName("probing")
    class Probing {
        @Test
        @DisplayName("reports subtitle streams with their codec")
        void probe() {
            var sink = MediaSink.memory();
            Subtitles.write(CUES, SubtitleMarkup.SUBRIP, ContainerFormat.SUBRIP, sink);
            var info = Media.probe(MediaSource.of(sink.toByteArray()));
            assertEquals(ContainerFormat.SUBRIP, info.container().orElseThrow());
            var stream = assertInstanceOf(DataStreamInfo.class, info.streams().getFirst());
            assertEquals(Codec.SUBRIP, stream.codec());
            var mkv = write(MatroskaMuxer::new, Codec.ASS, CUES, SubtitleMarkup.SUBRIP);
            var mkvStream = assertInstanceOf(DataStreamInfo.class, Media.probe(MediaSource.of(mkv)).streams().getFirst());
            assertEquals(Codec.ASS, mkvStream.codec());
        }
    }
}
