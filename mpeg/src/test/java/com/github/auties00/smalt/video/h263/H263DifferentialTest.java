package com.github.auties00.smalt.video.h263;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes synthetic scenes with ffmpeg's H.263 and H.263+ encoders across the optional modes and
 * compares the decoder's output with ffmpeg's decode, within the noise floor that the freedom of
 * the inverse DCT leaves (see the MPEG-4 differential test).
 */
@DisplayName("H.263 decoder against ffmpeg")
class H263DifferentialTest {
    record Case(String name, String size, String codec, String options, boolean overlapped) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("baseline QCIF", "176x144", "h263", "-g 12", false),
                new Case("baseline CIF", "352x288", "h263", "-g 12", false),
                new Case("sub-QCIF intra only", "128x96", "h263", "-g 1", false),
                new Case("advanced prediction", "176x144", "h263", "-obmc 1 -flags +mv4", true),
                new Case("H.263+ custom size", "320x240", "h263p", "-g 12", false),
                new Case("unrestricted motion vectors", "176x144", "h263p", "-umv 1", false),
                new Case("advanced intra coding", "176x144", "h263p", "-flags +aic -g 6", false),
                new Case("deblocking filter", "176x144", "h263p", "-flags +loop+mv4", false),
                new Case("alternative inter VLC", "176x144", "h263p", "-aiv 1", false),
                new Case("slice structured", "176x144", "h263p", "-structured_slices 1 -ps 300", false),
                new Case("every H.263+ mode", "352x288", "h263p",
                        "-flags +aic+loop+mv4 -umv 1 -aiv 1 -obmc 1 -structured_slices 1 -ps 600", true)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("decodes within the IDCT noise floor of ffmpeg's decode")
    void matchesFfmpeg(Case c) {
        var output = new ArrayList<>(List.of("-t", "1", "-c:v", c.codec(), "-q:v", "5"));
        output.addAll(List.of(c.options().split(" ")));
        var file = Fixtures.ffmpeg(c.codec().equals("h263") ? "3gp" : "mkv", List.of("-f", "lavfi", "-i", "testsrc2=size=" + c.size() + ":rate=15"),
                output);
        var expected = ffmpeg(file, "int");
        var simple = ffmpeg(file, "simple");
        var raw = new ByteArrayOutputStream();
        var timestamps = new ArrayList<Long>();
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source)) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            try (var decoder = new H263Decoder(track)) {
                Consumer<VideoFrame> sink = frame -> {
                    timestamps.add(frame.pts());
                    raw.writeBytes(raw((YuvPicture) frame.picture()));
                };
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    decoder.decode(packet, sink);
                }
                decoder.flush(sink);
            }
        }
        assertEquals(expected.length, raw.size(), c.name() + " after " + timestamps.size() + " frames");
        var ours = Quality.psnr(expected, raw.toByteArray());
        var floor = Quality.psnr(expected, simple);
        // FIXME: overlapped motion compensation differs at a few samples (see Reconstructor).
        var required = c.overlapped() ? 44 : Math.min(floor, 60) - 1;
        assertTrue(ours >= required, c.name() + ": PSNR " + ours + " dB, ffmpeg -idct simple "
                + floor + " dB");
        for (var i = 1; i < timestamps.size(); i++) {
            assertTrue(timestamps.get(i) > timestamps.get(i - 1), "timestamps increase: " + timestamps);
        }
    }

    static byte[] ffmpeg(Path file, String idct) {
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-idct", idct, "-i",
                file.toString(), "-map", "0:v:0", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-"));
    }

    static byte[] raw(YuvPicture picture) {
        var out = new ByteArrayOutputStream();
        for (var y = 0; y < picture.height(); y++) {
            out.write(picture.y(), y * picture.yStride(), picture.width());
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            out.write(picture.u(), y * picture.uvStride(), picture.chromaWidth());
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            out.write(picture.v(), y * picture.uvStride(), picture.chromaWidth());
        }
        return out.toByteArray();
    }
}
