package com.github.auties00.smalt.video.vc1;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoders;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the VC-1 samples of FFmpeg's FATE suite, which include SMPTE VC-1 conformance bitstreams of the advanced
 * profile (SA*, progressive and interlace field pictures), main profile streams in the RCV serialization (SMM*) and a
 * 1080i stream mixing interlace frame and field pictures, plus interlaced streams of FFmpeg's samples archive in AVI
 * and Blu-ray transport stream files, and compares every frame sample for sample with ffmpeg's decode.
 *
 * <p>VC-1 specifies its inverse transform, interpolation and filters exactly (SMPTE ST 421 Annex A and sections 8 and
 * 10), so conforming decoders reconstruct the same samples, which is what {@link Tolerance#exact()} checks.
 */
@DisplayName("VC-1 conformance against ffmpeg")
class Vc1ConformanceTest {
    static final Tolerance EXACT = Tolerance.exact();

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vc1-fate-SA00040.vc1", "vc1-fate-SA00050.vc1", "vc1-fate-SA10091.vc1",
            "vc1-fate-SA10143.vc1", "vc1-fate-SA20021.vc1", "vc1-fate-SMM0005.rcv", "vc1-fate-SMM0015.rcv",
            "vc1-fate-ilaced_twomv.vc1"})
    @DisplayName("decodes every elementary stream sample as ffmpeg does")
    void elementary(String name) throws IOException {
        var file = Conformance.fetchListed(name);
        var bytes = Files.readAllBytes(file);
        var stream = name.endsWith(".rcv") ? Vc1TestStreams.rcv(bytes) : Vc1TestStreams.elementary(bytes);
        var decoded = Vc1TestStreams.decode(stream);
        assertTrue(decoded.failures().isEmpty(), name + " decodes without errors: " + decoded.failures());
        assertTrue(decoded.count() > 0, name + " produces frames");
        var first = decoded.frames().getFirst();
        compare(name, Oracle.decodeVideo(file, "yuv420p", Integer.MAX_VALUE), decoded.raw(), first.width(),
                first.height(), decoded.count());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vc1-samples-glitch-ffvc1.avi", "vc1-samples-interlaced-bframes.m2ts"})
    @DisplayName("decodes the VC-1 track of a container file as ffmpeg does")
    void container(String name) throws IOException {
        var file = Conformance.fetchListed(name);
        var limit = name.endsWith(".m2ts") ? 40 : Integer.MAX_VALUE;
        var decoded = demuxAndDecode(file, limit);
        compare(name, Oracle.decodeVideo(file, "yuv420p", decoded.count()), decoded.raw(), decoded.width(),
                decoded.height(), decoded.count());
    }

    @ParameterizedTest(name = "{0} in {1}")
    @CsvSource({"vc1-fate-SMM0005.rcv, avi", "vc1-fate-SMM0005.rcv, mkv", "vc1-fate-SA10143.vc1, avi",
            "vc1-fate-SA10143.vc1, mkv", "vc1-fate-SA10143.vc1, mp4", "vc1-fate-SA10143.vc1, ts"})
    @DisplayName("decodes a stream ffmpeg copied into a container as ffmpeg decodes the elementary stream")
    void remuxed(String name, String extension, @TempDir Path directory) throws IOException {
        var file = Conformance.fetchListed(name);
        var remuxed = directory.resolve("remuxed." + extension);
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-y", "-i", file.toString(),
                "-map", "0:v:0", "-c", "copy", remuxed.toString()));
        var decoded = demuxAndDecode(remuxed, Integer.MAX_VALUE);
        compare(name + " in " + extension, Oracle.decodeVideo(file, "yuv420p", Integer.MAX_VALUE), decoded.raw(),
                decoded.width(), decoded.height(), decoded.count());
    }

    /**
     * The frames a container decode produced, with their size.
     */
    private record ContainerDecode(byte[] raw, int count, int width, int height) {
    }

    private static ContainerDecode demuxAndDecode(Path file, int limit) {
        var out = new ByteArrayOutputStream();
        var failures = new ArrayList<String>();
        var state = new int[3];
        Consumer<VideoFrame> sink = frame -> {
            if (state[0] < limit) {
                var picture = (YuvPicture) frame.picture();
                out.writeBytes(Vc1TestStreams.raw(picture));
                state[0]++;
                state[1] = picture.width();
                state[2] = picture.height();
            }
        };
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (VideoTrack) demuxer.tracks().stream().filter(t -> t.codec() == Codec.VC1).findFirst()
                    .orElseThrow();
            try (var decoder = VideoDecoders.create(track)) {
                for (var packet = demuxer.read(); packet != null && state[0] < limit; packet = demuxer.read()) {
                    if (packet.trackId() != track.id()) {
                        continue;
                    }
                    try {
                        decoder.decode(packet, sink);
                    } catch (MediaException.Malformed exception) {
                        failures.add(exception.getMessage());
                    }
                }
                decoder.flush(sink);
            }
        }
        assertTrue(failures.isEmpty(), file.getFileName() + " decodes without errors: " + failures);
        assertTrue(state[0] > 0, file.getFileName() + " produces frames");
        return new ContainerDecode(out.toByteArray(), state[0], state[1], state[2]);
    }

    private static void compare(String name, byte[] expected, byte[] actual, int width, int height, int count) {
        var detail = Vc1TestStreams.firstDifference(expected, actual, width, height);
        assertEquals(expected.length, actual.length, name + " decoded size after " + count + " frames; " + detail);
        EXACT.assertFrames(name + ": " + detail, expected, actual,
                width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2));
    }
}
