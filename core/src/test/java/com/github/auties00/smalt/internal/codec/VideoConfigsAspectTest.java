package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the sample aspect ratio a player applies to a track: the container's, else the one the
 * H.264 or HEVC configuration record signals, from x264 and x265 streams made through ffmpeg.
 */
@DisplayName("VideoConfigs.sampleAspect")
class VideoConfigsAspectTest {
    static VideoTrack track(String encoder, String sar) {
        var file = Fixtures.ffmpeg("mkv", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=0.2"),
                encoder.equals("libx265")
                        ? List.of("-vf", "setsar=" + sar, "-c:v", "libx265", "-x265-params", "log-level=error", "-pix_fmt",
                        "yuv420p")
                        : List.of("-vf", "setsar=" + sar, "-c:v", "libx264", "-x264-params", "threads=1", "-pix_fmt",
                        "yuv420p"));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(file)))) {
            return (VideoTrack) demuxer.tracks().getFirst();
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"libx264, 64/45, 64, 45", "libx264, 7/5, 7, 5", "libx265, 40/33, 40, 33", "libx265, 9/7, 9, 7"})
    @DisplayName("prefers the container, then falls back to the sequence parameter set")
    void fallback(String encoder, String sar, long width, long height) {
        var signaled = new Rational(width, height);
        var declared = track(encoder, sar);
        assertEquals(signaled, declared.sampleAspectRatio().orElseThrow());
        var silent = declared.toBuilder().noSampleAspectRatio().build();
        assertEquals(signaled, VideoConfigs.sampleAspect(silent));
        var overridden = declared.toBuilder().sampleAspectRatio(new Rational(3, 1)).build();
        assertEquals(new Rational(3, 1), VideoConfigs.sampleAspect(overridden));
    }

    @Test
    @DisplayName("declares nothing for a track without a ratio or configuration")
    void none() {
        var track = VideoTrack.builder().codec(Codec.VP9).timeBase(new Rational(1, 1000)).width(16).height(16).build();
        assertNull(VideoConfigs.sampleAspect(track));
        var unparsable = VideoTrack.builder().codec(Codec.H264).timeBase(new Rational(1, 1000)).codecConfig(new byte[]{1, 2})
                .build();
        assertNull(VideoConfigs.sampleAspect(unparsable));
    }
}
