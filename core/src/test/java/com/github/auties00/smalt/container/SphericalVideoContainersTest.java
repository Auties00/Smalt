package com.github.auties00.smalt.container;

import com.github.auties00.smalt.container.matroska.MatroskaDemuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.SphericalVideo;
import com.github.auties00.smalt.track.StereoMode;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the spherical projection and stereo mode of video tracks: the Spherical Video V2 {@code st3d} and
 * {@code sv3d} boxes of MP4 and the {@code StereoMode} and {@code Projection} elements of Matroska, written by the
 * muxers and read by the demuxers, read by ffprobe, and carried across containers by ffmpeg copies.
 */
@DisplayName("Spherical and stereoscopic video metadata")
class SphericalVideoContainersTest {
    private static final SphericalVideo EQUIRECTANGULAR = new SphericalVideo(
            new SphericalVideo.Equirectangular(0x10000000L, 0x20000000L, 0x0L, 0xFFFFFFF0L), 90, -45, 30, "Smalt test");

    private static final SphericalVideo CUBEMAP = new SphericalVideo(new SphericalVideo.Cubemap(0, 8), -90, 0, 0, "");

    private static final SphericalVideo MESH = new SphericalVideo(SphericalVideo.Mesh.of(new byte[]{1, 2, 3, 4, 5, 6, 7}),
            0, 12.5, -0.25, "");

    private static ContainerProbe.Demuxed source() {
        return MuxSupport.demux(Fixtures.read(Fixtures.testVideo("mkv", 64, 48, 1, List.of("-c:v", "mpeg4", "-an"))));
    }

    private static List<Track> with(List<Track> tracks, SphericalVideo spherical, StereoMode mode) {
        var result = new ArrayList<Track>();
        for (var track : tracks) {
            if (track instanceof VideoTrack video) {
                var builder = video.toBuilder();
                if (spherical != null) {
                    builder.spherical(spherical);
                }
                if (mode != null) {
                    builder.stereoMode(mode);
                }
                result.add(builder.build());
            } else {
                result.add(track);
            }
        }
        return result;
    }

    private static VideoTrack video(byte[] file, Function<MediaSource, Demuxer> factory) {
        try (var demuxer = factory.apply(MediaSource.of(file))) {
            return demuxer.tracks().stream().filter(VideoTrack.class::isInstance).map(VideoTrack.class::cast)
                    .findFirst().orElseThrow();
        }
    }

    private static byte[] mux(List<Track> tracks, List<com.github.auties00.smalt.track.Packet> packets,
                              Function<MediaSink, Muxer> factory) {
        return MuxSupport.toMemory(tracks, packets, factory);
    }

    private static String ffprobe(Path file) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        return new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-show_streams", "-select_streams", "v", "-of", "json", file.toString())), StandardCharsets.UTF_8);
    }

    private static Path ffmpegCopy(Path input, String name) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var output = input.resolveSibling(name);
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-y", "-i", input.toString(),
                "-map", "0", "-c", "copy", "-strict", "unofficial", output.toString()));
        return output;
    }

    static Stream<SphericalVideo> projections() {
        return Stream.of(EQUIRECTANGULAR, CUBEMAP, MESH, SphericalVideo.of(SphericalVideo.Equirectangular.FULL));
    }

    @ParameterizedTest
    @MethodSource("projections")
    @DisplayName("round trips a projection through Matroska, MP4 and fragmented MP4")
    void roundTrip(SphericalVideo spherical) {
        var source = source();
        var tracks = with(source.tracks(), spherical, StereoMode.TOP_BOTTOM_LEFT_FIRST);
        var matroska = mux(tracks, source.order(), MatroskaMuxer::new);
        var read = video(matroska, MatroskaDemuxer::new);
        // Matroska has no metadata source
        assertEquals(new SphericalVideo(spherical.projection(), spherical.yaw(), spherical.pitch(), spherical.roll(), ""),
                read.spherical().orElseThrow());
        assertEquals(StereoMode.TOP_BOTTOM_LEFT_FIRST, read.stereoMode().orElseThrow());
        for (var mp4 : List.of(mux(tracks, source.order(), Mp4Muxer::new), mux(tracks, source.order(), FragmentedMp4Muxer::new))) {
            var fromMp4 = video(mp4, Mp4Demuxer::new);
            assertEquals(spherical, fromMp4.spherical().orElseThrow());
            assertEquals(StereoMode.TOP_BOTTOM_LEFT_FIRST, fromMp4.stereoMode().orElseThrow());
        }
    }

    @ParameterizedTest
    @EnumSource(StereoMode.class)
    @DisplayName("writes every stereo mode to Matroska, and those st3d holds to MP4")
    void stereoModes(StereoMode mode) {
        var source = source();
        var tracks = with(source.tracks(), null, mode);
        assertEquals(mode, video(mux(tracks, source.order(), MatroskaMuxer::new), MatroskaDemuxer::new).stereoMode()
                .orElseThrow());
        var mp4 = video(mux(tracks, source.order(), Mp4Muxer::new), Mp4Demuxer::new);
        var stored = mode == StereoMode.MONO || mode == StereoMode.TOP_BOTTOM_LEFT_FIRST
                || mode == StereoMode.SIDE_BY_SIDE_LEFT_FIRST;
        assertEquals(stored ? mode : null, mp4.stereoMode().orElse(null));
        assertTrue(mp4.spherical().isEmpty());
    }

    @Test
    @DisplayName("ffprobe reads the projection and stereo mode of both containers, and ffmpeg copies carry them over")
    void ffprobe() {
        var source = source();
        var tracks = with(source.tracks(), EQUIRECTANGULAR, StereoMode.SIDE_BY_SIDE_LEFT_FIRST);
        var matroska = MuxSupport.save("spherical.mkv", mux(tracks, source.order(), MatroskaMuxer::new));
        var mp4 = MuxSupport.save("spherical.mp4", mux(tracks, source.order(), Mp4Muxer::new));
        for (var file : List.of(matroska, mp4)) {
            var probe = ffprobe(file);
            // ffprobe prints the bounds as pixel tile bounds, which the ffmpeg copies below check exactly
            for (var expected : List.of("\"side_data_type\": \"Spherical Mapping\"", "equirectangular\"",
                    "\"yaw\": 90", "\"pitch\": -45", "\"roll\": 30", "\"side_data_type\": \"Stereo 3D\"",
                    "\"type\": \"side by side\"")) {
                assertTrue(probe.contains(expected), file.getFileName() + " lacks " + expected + ": " + probe);
            }
        }
        var expected = new SphericalVideo(EQUIRECTANGULAR.projection(), 90, -45, 30, "");
        var copiedMp4 = video(Fixtures.read(ffmpegCopy(matroska, "spherical-copy.mp4")), Mp4Demuxer::new);
        assertEquals(expected.projection(), copiedMp4.spherical().orElseThrow().projection());
        assertEquals(List.of(90.0, -45.0, 30.0), List.of(copiedMp4.spherical().get().yaw(),
                copiedMp4.spherical().get().pitch(), copiedMp4.spherical().get().roll()));
        assertEquals(StereoMode.SIDE_BY_SIDE_LEFT_FIRST, copiedMp4.stereoMode().orElseThrow());
        var copiedMatroska = video(Fixtures.read(ffmpegCopy(mp4, "spherical-copy.mkv")), MatroskaDemuxer::new);
        assertEquals(expected, copiedMatroska.spherical().orElseThrow());
        assertEquals(StereoMode.SIDE_BY_SIDE_LEFT_FIRST, copiedMatroska.stereoMode().orElseThrow());
    }

    @Test
    @DisplayName("ffprobe reads a cubemap projection")
    void ffprobeCubemap() {
        var source = source();
        var tracks = with(source.tracks(), CUBEMAP, null);
        for (var file : List.of(MuxSupport.save("cubemap.mkv", mux(tracks, source.order(), MatroskaMuxer::new)),
                MuxSupport.save("cubemap.mp4", mux(tracks, source.order(), Mp4Muxer::new)))) {
            var probe = ffprobe(file);
            assertTrue(probe.contains("\"projection\": \"cubemap\"") && probe.contains("\"padding\": 8")
                    && probe.contains("\"yaw\": -90"), probe);
        }
    }
}
