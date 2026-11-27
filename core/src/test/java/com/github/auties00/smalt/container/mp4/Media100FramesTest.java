package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Reads a Media 100 ({@code dtPA}) QuickTime track: its frames come out as the Motion JPEG format B frames that
 * ffmpeg's {@code media100_to_mjpegb} bitstream filter makes of them, byte for byte.
 */
@DisplayName("Media 100 tracks")
class Media100FramesTest {
    @Test
    @DisplayName("rewrite each frame as ffmpeg's media100_to_mjpegb filter does")
    void matchesFfmpegFilter() throws NoSuchAlgorithmException {
        var file = Conformance.vector("mjpeg-fate-media100.mov");
        Oracle.assume(Oracle.Tool.FFMPEG);
        var framemd5 = new String(Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i",
                file.toString(), "-map", "0:v", "-c", "copy", "-bsf:v", "media100_to_mjpegb", "-f", "framemd5", "-")),
                StandardCharsets.US_ASCII);
        var expected = new ArrayList<String>();
        for (var line : framemd5.split("\r?\n")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                var fields = line.split(",");
                expected.add(fields[4].strip() + " " + fields[5].strip());
            }
        }
        var actual = new ArrayList<String>();
        try (var source = MediaSource.of(file); var demuxer = new Mp4Demuxer(source)) {
            var track = demuxer.tracks().stream().filter(candidate -> candidate.codec() == Codec.MJPEG).findFirst()
                    .orElseThrow();
            var digest = MessageDigest.getInstance("MD5");
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == track.id()) {
                    actual.add(packet.size() + " " + HexFormat.of().formatHex(digest.digest(packet.data())));
                }
            }
        }
        assertFalse(expected.isEmpty());
        assertEquals(expected, actual);
    }
}
