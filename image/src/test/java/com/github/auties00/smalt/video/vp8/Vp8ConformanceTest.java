package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes every libvpx VP8 test vector (vp80-00 to vp80-06) and compares the MD5 of each shown
 * frame's visible I420 planes with libvpx's {@code .ivf.md5} file, exactly as libvpx's
 * {@code test_vector_test.cc} does.
 */
@DisplayName("VP8 conformance (libvpx test vectors)")
class Vp8ConformanceTest {
    static Stream<String> vectors() {
        return Vp8Vectors.NAMES.stream();
    }

    static VideoTrack track(int width, int height) {
        return VideoTrack.builder().id(1).codec(Codec.VP8).timeBase(new Rational(1, 30)).width(width).height(height)
                .build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("every shown frame matches libvpx's MD5")
    void vector(String name) throws IOException {
        var ivfPath = Conformance.vector(name);
        var md5Path = Conformance.vector(name + ".md5");
        var expected = new ArrayList<String>();
        for (var line : Files.readString(md5Path, StandardCharsets.US_ASCII).split("\n")) {
            var trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                expected.add(trimmed.split("\\s+")[0]);
            }
        }
        var ivf = Ivf.parse(Files.readAllBytes(ivfPath));
        var actual = new ArrayList<String>();
        try (var decoder = new Vp8Decoder(track(ivf.width(), ivf.height()))) {
            for (var frame : ivf.frames()) {
                decoder.decode(new Packet(1, frame.data(), frame.pts(), frame.pts(), 0, false),
                        out -> actual.add(Ivf.md5(Ivf.i420((YuvPicture) out.picture()))));
            }
        }
        assertEquals(expected.size(), actual.size(), "shown frame count");
        for (var i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), "frame " + (i + 1));
        }
    }

    /**
     * VP8 streams of other encoders (ffmpeg's FATE samples: a real-world WebM, a mid-stream frame size change, a
     * stream using the sign bias of the golden and alternate references), the long real-world Tears of Steel stream
     * and Chromium's VP8 in Ogg ({@code sync2.ogv}, the OggVP8 mapping, with Vorbis): every shown frame's I420 MD5
     * must equal ffmpeg's decode of it ({@code -f framemd5}, frames kept at their own size), whose VP8 decoder is
     * bit-exact with libvpx's.
     */
    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"vp8-fate-RRSF49-short.webm",
            "vp8-fate-frame_size_change.webm", "vp8-fate-sintel-signbias.ivf", "tos_vp8.webm", "chromium-sync2.ogv"})
    @DisplayName("every shown frame of third-party streams matches ffmpeg's MD5")
    void thirdParty(String name) throws IOException {
        com.github.auties00.smalt.testkit.Oracle.assume(com.github.auties00.smalt.testkit.Oracle.Tool.FFMPEG);
        var path = Conformance.vector(name);
        var framemd5 = new String(com.github.auties00.smalt.testkit.Oracle.run(
                com.github.auties00.smalt.testkit.Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(20),
                java.util.List.of("-v", "error", "-i", path.toString(), "-map", "0:v:0", "-noautoscale", "-fps_mode", "passthrough",
                        "-pix_fmt", "yuv420p", "-f", "framemd5", "-")), StandardCharsets.US_ASCII);
        var expected = framemd5.lines().filter(line -> !line.startsWith("#") && !line.isBlank())
                .map(line -> line.substring(line.lastIndexOf(',') + 1).strip()).toList();
        var actual = new ArrayList<String>();
        var data = Files.readAllBytes(path);
        if (name.endsWith(".ivf")) {
            var ivf = Ivf.parse(data);
            try (var decoder = new Vp8Decoder(track(ivf.width(), ivf.height()))) {
                for (var frame : ivf.frames()) {
                    decoder.decode(new Packet(1, frame.data(), frame.pts(), frame.pts(), 0, false),
                            out -> actual.add(Ivf.md5(Ivf.i420((YuvPicture) out.picture()))));
                }
            }
        } else {
            try (var demuxer = com.github.auties00.smalt.container.Containers.openDemuxer(
                    com.github.auties00.smalt.io.MediaSource.of(data))) {
                var track = (VideoTrack) demuxer.tracks().stream().filter(VideoTrack.class::isInstance).findFirst()
                        .orElseThrow();
                try (var decoder = new Vp8Decoder(track)) {
                    for (var packet = read(demuxer, name); packet != null; packet = read(demuxer, name)) {
                        if (packet.trackId() == track.id()) {
                            decoder.decode(packet, out -> actual.add(Ivf.md5(Ivf.i420((YuvPicture) out.picture()))));
                        }
                    }
                }
            }
        }
        assertEquals(expected.size(), actual.size(), "shown frame count");
        for (var i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), "frame " + (i + 1));
        }
    }

    /**
     * Reads the next packet of a third-party file. {@code vp8-fate-RRSF49-short.webm} is cut short inside a block
     * group: the Matroska demuxer returns every complete block, then reports the cut block as malformed (its
     * documented contract), where the stream ends for the comparison, as it does for ffmpeg, which drops the cut
     * block.
     */
    private static Packet read(com.github.auties00.smalt.container.Demuxer demuxer, String name) {
        try {
            return demuxer.read();
        } catch (com.github.auties00.smalt.MediaException.Malformed exception) {
            if (!name.equals("vp8-fate-RRSF49-short.webm")) {
                throw exception;
            }
            assertTrue(exception.getMessage().contains("runs past the end of the input"), exception.getMessage());
            return null;
        }
    }
}
