package com.github.auties00.smalt.preview;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the thumbnails of anamorphic video: the picture is stretched by the sample aspect ratio
 * the container declares (or the H.264 sequence parameter set signals) before it is fitted to the
 * box, and matches ffmpeg's frame scaled to the same display size.
 */
@DisplayName("Thumbnails of anamorphic video")
class AnamorphicThumbnailsTest {
    static Path square() {
        return Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=1"),
                List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", "-x264-params", "threads=1"));
    }

    static Path file(String kind, Path directory) {
        return switch (kind) {
            case "mp4" -> Fixtures.ffmpeg("mp4", List.of("-i", square().toString()), List.of("-c", "copy", "-aspect", "16:9"));
            case "mkv" -> Fixtures.ffmpeg("mkv", List.of("-i", square().toString()), List.of("-c", "copy", "-aspect", "16:9"));
            case "avi" -> Fixtures.ffmpeg("avi", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=1"),
                    List.of("-c:v", "mpeg4", "-q:v", "2", "-aspect", "16:9"));
            case "vui" -> withoutPasp(Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i",
                    "testsrc2=size=176x144:rate=25:duration=1"), List.of("-vf", "setsar=16/11", "-c:v", "libx264", "-pix_fmt",
                    "yuv420p", "-x264-params", "threads=1")), directory.resolve("vui.mp4"));
            default -> throw new IllegalArgumentException(kind);
        };
    }

    /**
     * Rewrites an MP4 without its {@code pasp}, so that only the sequence parameter set signals the ratio.
     */
    static Path withoutPasp(Path input, Path output) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(input)));
             var sink = MediaSink.of(output);
             var muxer = new Mp4Muxer(sink)) {
            var video = (VideoTrack) demuxer.tracks().getFirst();
            muxer.addTrack(video.toBuilder().noSampleAspectRatio().build());
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return output;
    }

    @ParameterizedTest(name = "{0} in {1}x{2}")
    @CsvSource({"mp4, 0, 0, 256, 144", "mkv, 0, 0, 256, 144", "avi, 0, 0, 256, 144", "vui, 0, 0, 256, 144",
            "mp4, 128, 128, 128, 72", "mkv, 100, 300, 100, 56"})
    @DisplayName("stretches the frame to width * sample aspect ratio and matches ffmpeg's display rendering")
    void stretched(String kind, int boxWidth, int boxHeight, int width, int height, @TempDir Path directory) {
        var path = file(kind, directory);
        var options = boxWidth == 0 ? ThumbnailOptions.fullSize() : ThumbnailOptions.fitting(boxWidth, boxHeight);
        var thumbnail = Thumbnails.at(MediaSource.of(Fixtures.read(path)), 400_000, options);
        assertEquals(width, thumbnail.picture().width(), kind);
        assertEquals(height, thumbnail.picture().height(), kind);
        var psnr = ThumbnailsTest.psnr(thumbnail.picture(), ThumbnailsTest.reference(path, 400_000, width, height));
        assertTrue(psnr > 28, kind + " PSNR " + psnr);
    }
}
