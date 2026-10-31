package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.subtitle.CaptionScript;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the CEA-608 captions Smalt reads from H.264 video with those of ffmpeg's {@code cc_dec} (black box, its
 * {@code subcc} output of the {@code movie} source written as SubRip). The video is made by libx264, with and
 * without B pictures, and gets ATSC caption SEI messages in display order from the test kit's
 * {@link CaptionScript}: a pop-on caption with italics, a roll-up caption with a color, a paint-on caption with
 * special and extended characters.
 *
 * <p>Tolerance: the cues must carry the same text without markup, in the same order, and end within one frame
 * ({@link Tolerance#maxAbs(double)} of 34 ms); a cue may start later than ffmpeg's, as ffmpeg starts a roll-up or
 * paint-on caption at the end of the previous one, but never before it.
 */
@DisplayName("closed captions against ffmpeg")
class ClosedCaptionDifferentialTest {
    private static final Tolerance FRAME = Tolerance.maxAbs(34);
    private static final Pattern TIMING = Pattern.compile("(\\d+):(\\d+):(\\d+),(\\d+) --> (\\d+):(\\d+):(\\d+),(\\d+)");

    static CaptionScript script() {
        return new CaptionScript()
                .command(28, 1, 0x20).command(30, 1, 0x2E).preamble(32, 1, 15, 0x10 | 1 << 1)
                .text(34, 1, "Hello pop-on").control(42, 1, 0x11, 0x2E).text(44, 1, "italic").command(62, 1, 0x2F)
                .command(120, 1, 0x2C)
                .command(150, 1, 0x25).preamble(152, 1, 15, 0).text(154, 1, "Roll one").command(170, 1, 0x2D)
                .text(172, 1, "Roll two").control(180, 1, 0x11, 0x28).text(182, 1, "red").command(200, 1, 0x2D)
                .command(240, 1, 0x2C)
                .command(260, 1, 0x29).preamble(262, 1, 2, 0x10 | 2 << 1).text(264, 1, "Paint ")
                .control(268, 1, 0x11, 0x37).text(270, 1, "e").control(271, 1, 0x12, 0x30).command(300, 1, 0x2C);
    }

    record Cue(long start, long end, String text) {
    }

    static Path directory() {
        return Scratch.directory("caption-differential");
    }

    /**
     * Encodes ten seconds of video at 29.97 Hz, adds the captions of the script to every access unit in display
     * order, and writes it to Matroska.
     */
    static Path captioned(int bFrames) {
        var raw = directory().resolve("raw-" + bFrames + ".mkv");
        Oracle.assumeEncoder("libx264");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=s=96x64:r=30000/1001:d=11", "-c:v", "libx264", "-preset", "ultrafast",
                "-bf", Integer.toString(bFrames), "-pix_fmt", "yuv420p", raw.toString()));
        var frames = script().build(330, 2);
        var output = MediaSink.memory();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(raw));
             var muxer = new MatroskaMuxer(output)) {
            var video = demuxer.tracks().getFirst();
            muxer.addTrack(video);
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                var index = (int) Math.round(video.timeBase().toMicros(packet.pts()) * 30_000.0 / 1001 / 1_000_000);
                var data = index >= 0 && index < frames.length
                        ? CaptionScript.withSei(packet.data(), frames[index], false) : packet.data();
                muxer.writePacket(packet.withData(data));
            }
            muxer.finish();
        }
        var file = directory().resolve("captioned-" + bFrames + ".mkv");
        try {
            Files.write(file, output.toByteArray());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return file;
    }

    static List<Cue> ffmpeg(Path video) {
        var srt = directory().resolve(video.getFileName() + ".srt");
        var movie = video.toAbsolutePath().toString().replace('\\', '/').replace(":", "\\\\:");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "movie=" + movie + "[out0+subcc]", "-map", "0:1", srt.toString()));
        String text;
        try {
            text = Files.readString(srt, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var cues = new ArrayList<Cue>();
        for (var block : text.replace("\r\n", "\n").split("\n\n")) {
            var lines = block.strip().split("\n", 3);
            if (lines.length < 3) {
                continue;
            }
            var matcher = TIMING.matcher(lines[1]);
            assertTrue(matcher.matches(), lines[1]);
            cues.add(new Cue(millis(matcher, 1), millis(matcher, 5), Subtitles.plainText(lines[2],
                    SubtitleMarkup.SUBRIP)));
        }
        return cues;
    }

    static long millis(java.util.regex.Matcher matcher, int group) {
        return ((Long.parseLong(matcher.group(group)) * 60 + Long.parseLong(matcher.group(group + 1))) * 60
                + Long.parseLong(matcher.group(group + 2))) * 1000 + Long.parseLong(matcher.group(group + 3));
    }

    static List<Cue> smalt(Path video) {
        var cues = new ArrayList<Cue>();
        var options = SubtitleReaderOptions.builder().embeddedCaptions(true).build();
        try (var reader = SubtitleReader.open(MediaSource.of(video), options)) {
            assertEquals(com.github.auties00.smalt.track.Codec.CEA_708, reader.track().codec());
            for (var cue = reader.read(); cue != null; cue = reader.read()) {
                cues.add(new Cue(cue.startMicros() / 1000, cue.endMicros() / 1000,
                        Subtitles.plainText(cue.text(), SubtitleMarkup.SUBRIP)));
            }
        }
        return cues;
    }

    @ParameterizedTest(name = "{0} B pictures")
    @ValueSource(ints = {0, 2})
    @DisplayName("read the same captions as ffmpeg from H.264 caption SEI messages")
    void h264(int bFrames) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var video = captioned(bFrames);
        var expected = ffmpeg(video);
        var actual = smalt(video);
        assertEquals(expected.stream().map(Cue::text).map(String::strip).toList(),
                actual.stream().map(Cue::text).map(String::strip).toList());
        var expectedEnds = expected.stream().mapToInt(cue -> (int) cue.end()).toArray();
        var actualEnds = actual.stream().mapToInt(cue -> (int) cue.end()).toArray();
        FRAME.assertWithin("cue ends", expectedEnds, actualEnds);
        for (var index = 0; index < expected.size(); index++) {
            assertTrue(actual.get(index).start() >= expected.get(index).start() - 34,
                    "cue " + index + " starts at " + actual.get(index).start() + " before ffmpeg's "
                            + expected.get(index).start());
            assertTrue(actual.get(index).start() < actual.get(index).end());
        }
        assertEquals(5, actual.size(), actual.toString());
    }
}
