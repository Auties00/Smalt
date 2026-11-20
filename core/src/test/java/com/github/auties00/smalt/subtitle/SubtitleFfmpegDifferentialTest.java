package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.subtitle.AssDemuxer;
import com.github.auties00.smalt.container.subtitle.AssMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compares Smalt's SSA, ASS, TTML, SAMI, LRC and MicroDVD handling with ffmpeg's: scripts carried in Matroska by
 * ffmpeg and by Smalt hold the same blocks and header and write back the same script, and files each writes read the
 * same in the other. Times are compared to the millisecond, MicroDVD times to half a frame.
 */
@DisplayName("Subtitle files against ffmpeg")
class SubtitleFfmpegDifferentialTest {
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples", "subtitle");

    private static final Tolerance EXACT = Tolerance.exact();

    private static final List<SubtitleCue> CUES = List.of(
            SubtitleCue.of(1_000_000, 2_500_000, "Hello world\nline two"),
            SubtitleCue.of(3_000_000, 4_000_000, "Caf\u00E9 & <i>more</i>"),
            SubtitleCue.of(6_120_000, 7_000_000, "\u3053\u3093\u306B\u3061\u306F"));

    private static byte[] ffmpeg(List<String> arguments) {
        return ffmpeg(arguments, true);
    }

    private static byte[] ffmpeg(List<String> arguments, boolean strict) {
        var all = new ArrayList<>(strict ? List.of("-v", "error", "-xerror") : List.of("-v", "error"));
        all.addAll(arguments);
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), all);
    }

    private static List<SubtitleCue> srt(byte[] srt) {
        return Subtitles.read(MediaSource.of(srt), SubtitleReaderOptions.builder().markup(SubtitleMarkup.SUBRIP).build());
    }

    private static int[] millis(List<SubtitleCue> cues, boolean ends) {
        var result = new int[cues.size()];
        for (var index = 0; index < result.length; index++) {
            result[index] = (int) ((ends ? cues.get(index).endMicros() : cues.get(index).startMicros()) / 1000);
        }
        return result;
    }

    private static List<String> plain(List<SubtitleCue> cues) {
        var result = new ArrayList<String>();
        for (var cue : cues) {
            result.add(Subtitles.plainText(cue.text(), SubtitleMarkup.SUBRIP));
        }
        return result;
    }

    private static byte[] write(List<SubtitleCue> cues, ContainerFormat format) {
        var sink = MediaSink.memory();
        Subtitles.write(cues, SubtitleMarkup.SUBRIP, format, sink);
        return sink.toByteArray();
    }

    @Nested
    @DisplayName("SSA and ASS in Matroska")
    class Matroska {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"cues.ass", "cues.ssa"})
        @DisplayName("ffmpeg's blocks and header equal Smalt's, and Smalt writes the script ffmpeg carried back")
        void fromFfmpeg(String sample, @TempDir Path directory) throws IOException {
            var script = Files.readAllBytes(SAMPLES.resolve(sample));
            var mkv = directory.resolve("ffmpeg.mkv");
            ffmpeg(List.of("-i", SAMPLES.resolve(sample).toString(), "-c", "copy", mkv.toString()));
            var expected = new ArrayList<Packet>();
            String header;
            try (var demuxer = new AssDemuxer(MediaSource.of(script))) {
                header = new String(demuxer.tracks().getFirst().codecConfig().orElseThrow(), StandardCharsets.UTF_8);
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    expected.add(packet);
                }
            }
            var sink = MediaSink.memory();
            try (var demuxer = Containers.openDemuxer(MediaSource.of(Files.readAllBytes(mkv)));
                 var muxer = new AssMuxer(sink)) {
                var track = demuxer.tracks().getFirst();
                assertEquals(header.strip(), new String(track.codecConfig().orElseThrow(), StandardCharsets.UTF_8).strip());
                muxer.addTrack(track);
                var index = 0;
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read(), index++) {
                    var want = expected.get(index);
                    EXACT.assertWithin(sample + " block " + index, want.data(), packet.data());
                    EXACT.assertWithin(sample + " timing " + index, new int[]{(int) want.pts(), (int) want.duration()},
                            new int[]{(int) packet.pts(), (int) packet.duration()});
                    muxer.writePacket(packet);
                }
                assertEquals(expected.size(), index);
                muxer.finish();
            }
            EXACT.assertWithin(sample, script, sink.toByteArray());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"cues.ass", "cues.ssa"})
        @DisplayName("a Matroska file Smalt writes gives ffmpeg the script back")
        void toFfmpeg(String sample, @TempDir Path directory) throws IOException {
            var script = Files.readAllBytes(SAMPLES.resolve(sample));
            var sink = MediaSink.memory();
            try (var demuxer = new AssDemuxer(MediaSource.of(script)); var muxer = new MatroskaMuxer(sink)) {
                muxer.addTrack(demuxer.tracks().getFirst());
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            var mkv = directory.resolve("smalt.mkv");
            Files.write(mkv, sink.toByteArray());
            var back = ffmpeg(List.of("-i", mkv.toString(), "-map", "0:s:0", "-c", "copy", "-f", "ass", "-"));
            assertEquals(new String(script, StandardCharsets.UTF_8), new String(back, StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
    }

    @Nested
    @DisplayName("files ffmpeg writes")
    class FromFfmpeg {
        @Test
        @DisplayName("TTML documents and MP4 stpp tracks read as the cues of their SubRip source")
        void ttml(@TempDir Path directory) throws IOException {
            var source = directory.resolve("in.srt");
            Files.write(source, write(CUES, ContainerFormat.SUBRIP));
            var expected = srt(Files.readAllBytes(source));
            var plainCues = new ArrayList<String>();
            for (var cue : expected) {
                plainCues.add(Subtitles.plainText(cue.text(), SubtitleMarkup.SUBRIP));
            }
            for (var output : List.of("out.ttml", "out.mp4")) {
                var file = directory.resolve(output);
                ffmpeg(List.of("-i", source.toString(), "-c:s", "ttml", file.toString()));
                var cues = srt(Files.readAllBytes(file));
                assertEquals(plainCues, plain(cues), output);
                EXACT.assertWithin(output + " starts", millis(expected, false), millis(cues, false));
                EXACT.assertWithin(output + " ends", millis(expected, true), millis(cues, true));
            }
        }

        @Test
        @DisplayName("LRC files read with the times of their SubRip source")
        void lrc(@TempDir Path directory) throws IOException {
            var source = directory.resolve("in.srt");
            Files.write(source, write(CUES, ContainerFormat.SUBRIP));
            var lrc = ffmpeg(List.of("-i", source.toString(), "-f", "lrc", "-"));
            var cues = srt(lrc);
            assertEquals(plain(CUES), plain(cues));
            EXACT.assertWithin("lrc starts", millis(CUES, false), millis(cues, false));
        }
    }

    @Nested
    @DisplayName("files Smalt writes")
    class ToFfmpeg {
        private List<SubtitleCue> throughFfmpeg(Path directory, String name, byte[] file) throws IOException {
            var path = directory.resolve(name);
            Files.write(path, file);
            // ffmpeg's SAMI decoder reports the &nbsp; paragraphs that clear the screen as errors and skips them
            var output = ffmpeg(List.of("-i", path.toString(), "-map", "0:s:0", "-f", "srt", "-"), !name.endsWith(".smi"));
            // and it leaves character references undecoded
            return srt(name.endsWith(".smi") ? new String(output, StandardCharsets.UTF_8).replace("&amp;", "&")
                    .getBytes(StandardCharsets.UTF_8) : output);
        }

        @Test
        @DisplayName("are read by ffmpeg with the same text and times")
        void read(@TempDir Path directory) throws IOException {
            for (var format : List.of(ContainerFormat.ASS, ContainerFormat.SAMI)) {
                var name = format == ContainerFormat.ASS ? "out.ass" : "out.smi";
                var cues = throughFfmpeg(directory, name, write(CUES, format));
                assertEquals(plain(CUES), plain(cues), name);
                EXACT.assertWithin(name + " starts", millis(CUES, false), millis(cues, false));
                EXACT.assertWithin(name + " ends", millis(CUES, true), millis(cues, true));
            }
            // ffmpeg returns each line of an LRC cue as a cue of its own and the clearing lines as empty cues
            var lrc = throughFfmpeg(directory, "out.lrc", write(CUES, ContainerFormat.LRC));
            var starts = lrc.stream().filter(cue -> !cue.text().isBlank()).mapToInt(cue -> (int) (cue.startMicros() / 1000))
                    .distinct().toArray();
            EXACT.assertWithin("lrc starts", millis(CUES, false), starts);
            var microDvd = throughFfmpeg(directory, "out.sub", write(CUES, ContainerFormat.MICRODVD));
            assertEquals(plain(CUES), plain(microDvd));
            // half a frame of 24000/1001 frames per second, in milliseconds
            Tolerance.maxAbs(21).assertWithin("microdvd starts", millis(CUES, false), millis(microDvd, false));
            Tolerance.maxAbs(21).assertWithin("microdvd ends", millis(CUES, true), millis(microDvd, true));
        }
    }
}
