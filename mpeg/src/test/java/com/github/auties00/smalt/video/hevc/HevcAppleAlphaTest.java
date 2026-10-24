package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoLayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes HEVC with alpha written by Apple's AVFoundation encoder, a black box: avconvert's
 * {@code PresetHEVCHighestQualityWithAlpha} codes a ProRes 4444 clip with an alpha channel, which ffmpeg made from a
 * test pattern over a radial alpha gradient, into a QuickTime file whose HEVC track carries the alpha as an auxiliary
 * layer with an alpha channel information SEI message. The color of the primary layer must match ffmpeg's decode of
 * it exactly; the attached alpha plane, which ffmpeg cannot decode, must stay close to the source's alpha channel. The
 * file is kept in {@code target/media-fixtures} for inspection.
 */
@EnabledOnOs(value = OS.MAC, disabledReason = "[platform:macos] AVFoundation's HEVC with alpha encoder runs only on macOS")
class HevcAppleAlphaTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final int FRAMES = 8;

    @Test
    @DisplayName("attaches the alpha layer of an AVFoundation HEVC with alpha file, its color matching ffmpeg's")
    void avconvert() throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        Oracle.assume("avconvert");
        var directory = Scratch.directory("apple-alpha");
        var source = directory.resolve("source.mov");
        var output = directory.resolve("apple-hevc-alpha.mov");
        Files.deleteIfExists(output);
        // a radial alpha gradient over the moving pattern, transparent at the corners
        var pattern = "testsrc2=size=%dx%d:rate=25,format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)'"
                .formatted(WIDTH, HEIGHT) + ":a='clip(255*(1.3-hypot(X-%d,Y-%d)/%d),0,255)'"
                .formatted(WIDTH / 2, HEIGHT / 2, WIDTH / 2);
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-y", "-f", "lavfi", "-i", pattern,
                "-frames:v", Integer.toString(FRAMES), "-c:v", "prores_ks", "-profile:v", "4444", "-pix_fmt",
                "yuva444p10le", source.toString()));
        Oracle.run("avconvert", Duration.ofMinutes(3), null, List.of("--source", source.toString(), "--output",
                output.toString(), "--preset", "PresetHEVCHighestQualityWithAlpha"));
        var file = Files.readAllBytes(output);
        Scratch.share(Path.of("target", "media-fixtures", "apple-hevc-alpha.mov"), file);
        var sourceAlpha = alphaOf(Oracle.decodeVideo(source, "rgba", FRAMES));

        var frames = new ArrayList<VideoFrame>();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = demuxer.tracks().stream().filter(VideoTrack.class::isInstance).map(VideoTrack.class::cast)
                    .filter(candidate -> candidate.codec() == Codec.HEVC).findFirst().orElseThrow();
            try (var decoder = new HevcDecoder(track)) {
                // the decoder recycles the samples of a frame once the consumer returns
                Consumer<VideoFrame> sink = frame -> frames.add(new VideoFrame(
                        HevcMultilayerVectorTest.snapshot(frame.picture()), frame.pts(), frame.duration(),
                        frame.keyframe(), frame.sideData()));
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    if (packet.trackId() == track.id()) {
                        decoder.decode(packet, sink);
                    }
                }
                decoder.flush(sink);
            }
        }
        assertEquals(sourceAlpha.size(), frames.size(), "frames");
        var colors = Oracle.decodeVideo(output, frames.getFirst().picture().bitDepth() > 8 ? "yuv420p10le" : "yuv420p",
                FRAMES);
        var frameSize = colors.length / frames.size();
        for (var i = 0; i < frames.size(); i++) {
            var frame = frames.get(i);
            var picture = frame.picture();
            assertTrue(picture.hasAlpha(), "frame " + i + " has alpha");
            assertEquals(VideoLayer.Type.PRIMARY, frame.sideData().layer().map(VideoLayer::type)
                    .orElse(VideoLayer.Type.PRIMARY), "frame " + i + " layer");
            var alpha = alphaOf(picture);
            var error = meanError(sourceAlpha.get(i), alpha);
            assertTrue(error < 4, "frame " + i + " alpha differs from the source by " + error + " on average");
            if (picture instanceof YuvPicture || picture instanceof com.github.auties00.smalt.image.Yuv16Picture) {
                assertArrayEquals(Arrays.copyOfRange(colors, i * frameSize, (i + 1) * frameSize),
                        HevcTestStreams.raw(picture), "frame " + i + " color");
            }
        }
    }

    private static List<byte[]> alphaOf(byte[] rgba) {
        var size = WIDTH * HEIGHT;
        var frames = new ArrayList<byte[]>();
        for (var start = 0; start + size * 4 <= rgba.length; start += size * 4) {
            var alpha = new byte[size];
            for (var i = 0; i < size; i++) {
                alpha[i] = rgba[start + i * 4 + 3];
            }
            frames.add(alpha);
        }
        return frames;
    }

    private static byte[] alphaOf(Picture picture) {
        var alpha = new byte[WIDTH * HEIGHT];
        switch (picture) {
            case YuvPicture yuv -> {
                for (var y = 0; y < HEIGHT; y++) {
                    System.arraycopy(yuv.alpha(), y * yuv.alphaStride(), alpha, y * WIDTH, WIDTH);
                }
            }
            case RgbaPicture rgba -> {
                for (var y = 0; y < HEIGHT; y++) {
                    for (var x = 0; x < WIDTH; x++) {
                        alpha[y * WIDTH + x] = rgba.pixels()[y * rgba.stride() + x * 4 + 3];
                    }
                }
            }
            case com.github.auties00.smalt.image.Yuv16Picture yuv -> {
                var shift = yuv.bitDepth() - 8;
                for (var y = 0; y < HEIGHT; y++) {
                    for (var x = 0; x < WIDTH; x++) {
                        alpha[y * WIDTH + x] = (byte) ((yuv.alpha()[y * yuv.alphaStride() + x] & 0xFFFF) >> shift);
                    }
                }
            }
            default -> throw new AssertionError("unexpected picture " + picture);
        }
        return alpha;
    }

    private static double meanError(byte[] expected, byte[] actual) {
        long sum = 0;
        for (var i = 0; i < expected.length; i++) {
            sum += Math.abs((expected[i] & 0xFF) - (actual[i] & 0xFF));
        }
        return (double) sum / expected.length;
    }
}
