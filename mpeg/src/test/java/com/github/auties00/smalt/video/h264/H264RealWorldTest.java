package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes H.264 streams of real encoders of natural content and compares every frame with ffmpeg's decode of the
 * same file (its {@code framemd5} output of planar 8-bit 4:2:0 samples): Netflix's x264 encode of the Sintel
 * "Weapon" scene (High profile, 1920x816, QP 32, an Annex B stream), the Big Buck Bunny trailer written by Apple's
 * QuickTime encoder (Main profile 1080p in a {@code .mov}, read through the QuickTime demuxer) and Netflix's
 * Meridian HDR master (High profile level 5.1, 3840x2160 at 59.94 frames per second, in MP4; the first frames), and
 * the main clip of the Sintel Blu-ray disc (BD H.264 High@4.1 1080p in a BDAV MPEG-2 transport stream of 192-byte
 * packets, the first 30 MiB of the clip with the disc's navigation files, see {@code tools/testdata/README.md}).
 */
@DisplayName("H.264 real-world streams")
class H264RealWorldTest {
    /** Returns ffmpeg's per-frame MD5s of the first frames of the first video stream of a file. */
    static List<String> ffmpegFrameMd5s(Path file, int frames) {
        var arguments = new ArrayList<>(List.of("-v", "error", "-i", file.toString(), "-map", "0:v:0"));
        if (frames > 0) {
            arguments.addAll(List.of("-frames:v", Integer.toString(frames)));
        }
        arguments.addAll(List.of("-pix_fmt", "yuv420p", "-f", "framemd5", "-"));
        var text = new String(Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(10), arguments),
                StandardCharsets.US_ASCII);
        var result = new ArrayList<String>();
        for (var line : text.split("\\R")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                var fields = line.split(",");
                result.add(fields[fields.length - 1].strip());
            }
        }
        return result;
    }

    /** Collects the MD5 of the planar samples of each frame, up to a count. */
    static final class Md5s implements Consumer<VideoFrame> {
        final List<String> md5s = new ArrayList<>();

        final int limit;

        int width;

        int height;

        Md5s(int limit) {
            this.limit = limit;
        }

        @Override
        public void accept(VideoFrame frame) {
            if (limit > 0 && md5s.size() >= limit) {
                return;
            }
            var picture = (YuvPicture) frame.picture();
            width = picture.width();
            height = picture.height();
            try {
                md5s.add(HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                        .digest(H264TestStreams.serialize(picture))));
            } catch (NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    static void assertSameFrames(String name, List<String> expected, List<String> actual) {
        for (var i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                fail(name + ": frame " + i + " differs from ffmpeg");
            }
        }
        assertEquals(expected.size(), actual.size(), name + " frame count");
    }

    @Test
    @DisplayName("Netflix's x264 encode of Sintel decodes every frame as ffmpeg does")
    void netflixSintel() {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = Conformance.vector("h264-netflix-Sintel_Weapon_1920x816_QP32.264");
        var sink = new Md5s(0);
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), H264DecoderOptions.defaults())) {
            for (var unit : H264TestStreams.accessUnits(H264TestStreams.nalUnits(Fixtures.read(file)))) {
                decoder.decode(new Packet(1, H264TestStreams.annexB(unit), Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP,
                        0, false), sink);
            }
            decoder.flush(sink);
        }
        assertEquals(156, sink.md5s.size(), "frames");
        assertEquals(1920 + "x" + 816, sink.width + "x" + sink.height);
        assertSameFrames("Sintel", ffmpegFrameMd5s(file, 0), sink.md5s);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"h264-blender-bbb-trailer_1080p.mov, 0, 1920, 1080",
            "h264-netflix-Meridian_UHD4k5994_HDR_P3PQ.mp4, 90, 3840, 2160",
            "bluray-sintel-cut-00000.m2ts, 48, 1920, 1080"})
    @DisplayName("files of other encoders demux and decode as ffmpeg decodes them")
    void containers(String vector, int frames, int width, int height) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = Conformance.vector(vector);
        var sink = new Md5s(frames);
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (VideoTrack) demuxer.tracks().stream().filter(candidate -> candidate instanceof VideoTrack)
                    .findFirst().orElseThrow();
            assertEquals(Codec.H264, track.codec(), vector);
            try (var decoder = new H264Decoder(track, H264DecoderOptions.defaults())) {
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (packet.trackId() == track.id()) {
                        decoder.decode(packet, sink);
                    }
                    if (frames > 0 && sink.md5s.size() >= frames) {
                        break;
                    }
                }
                if (frames == 0 || sink.md5s.size() < frames) {
                    decoder.flush(sink);
                }
            }
        }
        assertTrue(!sink.md5s.isEmpty(), vector + " decoded no frame");
        assertEquals(width + "x" + height, sink.width + "x" + sink.height, vector);
        assertSameFrames(vector, ffmpegFrameMd5s(file, frames), sink.md5s);
    }

    @Test
    @DisplayName("the Blu-ray navigation files name the main clip of the cut")
    void blurayNavigation() throws java.io.IOException {
        var index = java.nio.file.Files.readAllBytes(Conformance.vector("bluray-sintel-cut-index.bdmv"));
        assertEquals("INDX", new String(index, 0, 4, StandardCharsets.US_ASCII), "index.bdmv type indicator");
        var movie = java.nio.file.Files.readAllBytes(Conformance.vector("bluray-sintel-cut-MovieObject.bdmv"));
        assertEquals("MOBJ", new String(movie, 0, 4, StandardCharsets.US_ASCII), "MovieObject.bdmv type indicator");
        var playlist = java.nio.file.Files.readAllBytes(Conformance.vector("bluray-sintel-cut-00000.mpls"));
        assertEquals("MPLS", new String(playlist, 0, 4, StandardCharsets.US_ASCII), "playlist type indicator");
        assertTrue(new String(playlist, StandardCharsets.US_ASCII).contains("00000M2TS"), "the playlist plays 00000.m2ts");
        var clip = java.nio.file.Files.readAllBytes(Conformance.vector("bluray-sintel-cut-00000.clpi"));
        assertEquals("HDMV", new String(clip, 0, 4, StandardCharsets.US_ASCII), "clip information type indicator");
        var stream = java.nio.file.Files.readAllBytes(Conformance.vector("bluray-sintel-cut-00000.m2ts"));
        assertEquals(0, stream.length % 6144, "whole aligned units");
        for (var offset = 0; offset < stream.length; offset += 192) {
            if (stream[offset + 4] != 0x47) {
                fail("no sync byte in the BDAV packet at " + offset);
            }
        }
    }
}
