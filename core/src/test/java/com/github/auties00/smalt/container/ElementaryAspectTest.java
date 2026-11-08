package com.github.auties00.smalt.container;

import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the sample aspect ratio the MPEG-4 Part 2 and H.263 elementary stream demuxer declares from the video
 * object layer and picture headers of ffmpeg-made streams, against ffprobe's reading of the same file.
 */
@DisplayName("Sample aspect ratio of MPEG-4 Part 2 and H.263 elementary streams")
class ElementaryAspectTest {
    static Optional<Rational> declared(Path file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            return ((VideoTrack) demuxer.tracks().getFirst()).sampleAspectRatio();
        }
    }

    static String probe(Path file, String format) {
        return new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error", "-f", format,
                "-select_streams", "v:0", "-show_entries", "stream=sample_aspect_ratio", "-of",
                "default=noprint_wrappers=1:nokey=1", file.toString())), StandardCharsets.UTF_8).strip();
    }

    @ParameterizedTest(name = "{0} at {1}")
    @CsvSource({"mpeg4, 176x144, 1/1, 1:1", "mpeg4, 176x144, 12/11, 12:11", "mpeg4, 176x144, 40/33, 40:33",
            "mpeg4, 176x144, 64/45, 64:45", "mpeg4, 160x120, 4/3, 4:3"})
    @DisplayName("reads aspect_ratio_info and the extended pixel aspect ratio of the video object layer")
    void mpeg4(String encoder, String size, String sar, String expected) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var file = Fixtures.ffmpeg("m4v", List.of("-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=15:duration=0.2"),
                List.of("-vf", "setsar=" + sar, "-c:v", encoder, "-f", "m4v"));
        assertEquals(expected, probe(file, "m4v"));
        var terms = expected.split(":");
        assertEquals(Optional.of(new Rational(Long.parseLong(terms[0]), Long.parseLong(terms[1]))), declared(file));
    }

    @ParameterizedTest(name = "{0} at {1} {2}")
    @CsvSource({"h263, 176x144, 1/1, 12:11", "h263p, 176x144, 12/11, 12:11", "h263p, 160x120, 1/1, 1:1",
            "h263p, 160x120, 40/33, 40:33", "h263p, 160x120, 4/3, 4:3"})
    @DisplayName("reads the pixel aspect ratio of the custom picture format and the 12:11 of the standard formats")
    void h263(String encoder, String size, String sar, String expected) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var file = Fixtures.ffmpeg("263", List.of("-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=15:duration=0.2"),
                List.of("-vf", "setsar=" + sar, "-c:v", encoder, "-f", "rawvideo"));
        assertEquals(expected, probe(file, "h263"));
        var terms = expected.split(":");
        assertEquals(Optional.of(new Rational(Long.parseLong(terms[0]), Long.parseLong(terms[1]))), declared(file));
    }
}
