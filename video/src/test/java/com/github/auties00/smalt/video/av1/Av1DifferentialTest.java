package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoReader;
import com.github.auties00.smalt.video.av1.internal.Av1Context;
import com.github.auties00.smalt.video.av1.internal.Av1Picture;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes natural and synthetic clips with ffmpeg's AV1 encoders (libaom at real-time and good
 * quality speeds, with tiles, screen content tools, super-resolution, film grain, lossless, error
 * resilience, monochrome, 4:2:2, 4:4:4 and odd sizes; rav1e and SVT-AV1 when an ffmpeg build with them is on the
 * PATH) into IVF, MP4 and WebM, and requires every decoded frame to equal ffmpeg's decode of the
 * same file byte for byte. 10-bit and 12-bit streams are compared on the decoder's 16-bit planes.
 */
@DisplayName("AV1 decoder differential against ffmpeg")
class Av1DifferentialTest {
    /** AV1 reconstruction is normative, so every frame equals ffmpeg's decode. */
    static final Tolerance TOLERANCE = Tolerance.exact();

    private static final String FOREMAN_URL = "https://media.xiph.org/video/derf/y4m/foreman_cif.y4m";
    private static final String FOREMAN_SHA256 = "b0e3c35eff9fe1da91521a02eb9f0e3ef4bc5aec341ecef1567cd87f1711267b";

    record Case(String name, String extension, List<String> input, List<String> output, String pixelFormat) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static List<String> foreman(int frames, String filter) {
        var input = new ArrayList<>(List.of("-i", Conformance.fetch("foreman_cif.y4m", FOREMAN_URL, FOREMAN_SHA256).toString(),
                "-frames:v", Integer.toString(frames)));
        if (filter != null) {
            input.addAll(List.of("-vf", filter));
        }
        return input;
    }

    private static List<String> aom(String... options) {
        var output = new ArrayList<>(List.of("-c:v", "libaom-av1", "-threads", "4"));
        output.addAll(List.of(options));
        return output;
    }

    static Stream<Case> eightBit() {
        return Stream.of(
                new Case("realtime cpu-used 8", "ivf", foreman(30, null),
                        aom("-usage", "realtime", "-cpu-used", "8", "-b:v", "300k", "-g", "15"), "yuv420p"),
                new Case("realtime cpu-used 8 tiles 2x2", "webm", foreman(20, null),
                        aom("-usage", "realtime", "-cpu-used", "8", "-b:v", "500k", "-tiles", "2x2", "-row-mt", "1"),
                        "yuv420p"),
                new Case("good cpu-used 4", "mp4", foreman(12, null),
                        aom("-cpu-used", "4", "-crf", "30", "-g", "8", "-lag-in-frames", "8"), "yuv420p"),
                new Case("good cpu-used 6 tiles 4x2", "mp4", foreman(10, "scale=640:480"),
                        aom("-cpu-used", "6", "-crf", "35", "-tiles", "4x2"), "yuv420p"),
                new Case("screen content", "ivf",
                        List.of("-f", "lavfi", "-i", "life=s=320x240:mold=10:r=30:ratio=0.1:death_color=#C83232:"
                                + "life_color=#00ff00", "-frames:v", "20"),
                        aom("-pix_fmt", "yuv420p", "-cpu-used", "6", "-crf", "30", "-g", "5", "-aom-params",
                                "tune-content=screen", "-enable-intrabc", "1", "-enable-palette", "1"), "yuv420p"),
                new Case("odd size 97x61", "ivf", foreman(10, "scale=97:61"),
                        aom("-cpu-used", "6", "-crf", "25"), "yuv420p"),
                new Case("film grain", "ivf", foreman(8, null),
                        aom("-cpu-used", "6", "-crf", "30", "-denoise-noise-level", "30"), "yuv420p"),
                new Case("film grain 720p", "ivf", foreman(4, "scale=1280:720"),
                        aom("-cpu-used", "6", "-crf", "30", "-denoise-noise-level", "25", "-tiles", "2x2"), "yuv420p"),
                new Case("lossless", "ivf", foreman(4, "scale=176:144"),
                        aom("-cpu-used", "6", "-aom-params", "lossless=1"), "yuv420p"),
                new Case("error resilient", "ivf", foreman(10, null),
                        aom("-usage", "realtime", "-cpu-used", "8", "-b:v", "300k", "-aom-params", "error-resilient=1"),
                        "yuv420p"),
                new Case("monochrome", "ivf", foreman(8, "format=gray"),
                        aom("-cpu-used", "6", "-crf", "30"), "gray"),
                new Case("4:4:4", "ivf", foreman(8, "format=yuv444p"),
                        aom("-cpu-used", "6", "-crf", "30"), "yuv444p"),
                new Case("4:2:2 tiles 2x2", "mkv", foreman(8, "format=yuv422p"),
                        aom("-cpu-used", "6", "-crf", "30", "-tiles", "2x2"), "yuv422p"),
                new Case("delta quantizer and loop filter tiles 2x2", "ivf", foreman(16, null),
                        aom("-cpu-used", "6", "-b:v", "400k", "-tiles", "2x2", "-aq-mode", "1", "-aom-params",
                                "deltaq-mode=1:delta-lf-mode=1"), "yuv420p"));
    }

    static Stream<Case> highBitDepth() {
        return Stream.of(
                new Case("10-bit realtime", "ivf", foreman(20, "format=yuv420p10le"),
                        aom("-usage", "realtime", "-cpu-used", "8", "-b:v", "400k"), "yuv420p10le"),
                new Case("10-bit good with film grain", "ivf", foreman(8, "format=yuv420p10le"),
                        aom("-cpu-used", "6", "-crf", "30", "-denoise-noise-level", "20"), "yuv420p10le"),
                new Case("10-bit monochrome", "ivf", foreman(6, "format=gray10le"),
                        aom("-cpu-used", "6", "-crf", "30"), "gray10le"),
                new Case("10-bit 4:2:2", "ivf", foreman(8, "format=yuv422p10le"),
                        aom("-cpu-used", "6", "-crf", "30"), "yuv422p10le"),
                new Case("12-bit 4:2:0", "ivf", foreman(8, "format=yuv420p12le"),
                        aom("-cpu-used", "6", "-crf", "30"), "yuv420p12le"),
                new Case("10-bit 4:2:0 720p with film grain", "ivf", foreman(4, "scale=1280:720,format=yuv420p10le"),
                        aom("-cpu-used", "6", "-crf", "30", "-denoise-noise-level", "25"), "yuv420p10le"),
                new Case("12-bit 4:4:4 with film grain", "ivf", foreman(6, "format=yuv444p12le"),
                        aom("-cpu-used", "6", "-crf", "30", "-denoise-noise-level", "20"), "yuv444p12le"));
    }

    static Stream<Case> otherEncoders() {
        return Stream.of(
                new Case("rav1e speed 10", "ivf", foreman(12, null),
                        List.of("-c:v", "librav1e", "-speed", "10", "-qp", "100"), "yuv420p"),
                new Case("rav1e speed 6 tiles", "ivf", foreman(8, null),
                        List.of("-c:v", "librav1e", "-speed", "6", "-qp", "80", "-tiles", "4"), "yuv420p"),
                new Case("svt-av1 preset 10", "ivf", foreman(12, null),
                        List.of("-c:v", "libsvtav1", "-preset", "10", "-crf", "35"), "yuv420p"),
                new Case("svt-av1 super-resolution", "ivf", foreman(8, null),
                        List.of("-c:v", "libsvtav1", "-preset", "10", "-crf", "35", "-svtav1-params",
                                "superres-mode=1:superres-denom=13:superres-kf-denom=13"), "yuv420p"),
                new Case("svt-av1 preset 12 10-bit", "ivf", foreman(8, null),
                        List.of("-c:v", "libsvtav1", "-preset", "12", "-crf", "35", "-pix_fmt", "yuv420p10le"),
                        "yuv420p"));
    }

    /**
     * Decodes a file with the public reader into raw planar frames as ffmpeg writes them.
     */
    private static byte[] decodePublic(Path file) {
        var out = new ByteArrayOutputStream();
        try (var source = MediaSource.of(Files.readAllBytes(file));
             var reader = VideoReader.open(source)) {
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                var picture = (YuvPicture) frame.picture();
                write(out, picture.y(), picture.yStride(), picture.width(), picture.height());
                if (picture.u() != null) {
                    write(out, picture.u(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight());
                    write(out, picture.v(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight());
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, byte[] plane, int stride, int w, int h) {
        for (var y = 0; y < h; y++) {
            out.write(plane, y * stride, w);
        }
    }

    /**
     * Decodes a file with the internal decoder into 16-bit little-endian planar frames.
     */
    private static byte[] decodeHighBitDepth(Path file) {
        var out = new ByteArrayOutputStream();
        try (var source = MediaSource.of(Files.readAllBytes(file));
             var demuxer = Containers.openDemuxer(source)) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            var context = new Av1Context(MediaLimits.DEFAULT);
            track.codecConfig().ifPresent(context::decodeConfig);
            var pictures = new ArrayList<Av1Picture>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                pictures.clear();
                context.decode(packet.data(), 0, packet.data().length, packet.pts(), 0, pictures);
                for (var p : pictures) {
                    write16(out, p.data[0], p.stride[0], p.w, p.h);
                    if (p.data[1] != null) {
                        var ssHor = p.layout == SequenceHeader.LAYOUT_I444 ? 0 : 1;
                        var ssVer = p.layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
                        var cw = (p.w + ssHor) >> ssHor;
                        var ch = (p.h + ssVer) >> ssVer;
                        write16(out, p.data[1], p.stride[1], cw, ch);
                        write16(out, p.data[2], p.stride[1], cw, ch);
                    }
                    p.release();
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return out.toByteArray();
    }

    private static void write16(ByteArrayOutputStream out, short[] plane, int stride, int w, int h) {
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                var v = plane[y * stride + x];
                out.write(v & 0xFF);
                out.write((v >> 8) & 0xFF);
            }
        }
    }

    private static void assertSame(byte[] expected, byte[] actual, String label) {
        assertTrue(expected.length > 0, "ffmpeg produced no frames");
        assertEquals(expected.length, actual.length, label + ": decoded size");
        TOLERANCE.assertWithin(label, expected, actual);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eightBit")
    @DisplayName("8-bit libaom streams decode exactly like ffmpeg")
    void eightBit(Case c) {
        var file = Fixtures.ffmpeg(c.extension(), c.input(), c.output());
        var expected = Oracle.decodeVideo(file, c.pixelFormat(), 0);
        assertSame(expected, decodePublic(file), c.name());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eightBit")
    @DisplayName("8-bit libaom streams decode exactly like ffmpeg with several frames in flight")
    void eightBitFrameThreaded(Case c) {
        var file = Fixtures.ffmpeg(c.extension(), c.input(), c.output());
        var expected = Oracle.decodeVideo(file, c.pixelFormat(), 0);
        assertSame(expected, decodeDelayed(file, 4), c.name());
    }

    /**
     * Decodes a file with a decoder working on up to {@code frameDelay} frames at once into raw
     * planar frames as ffmpeg writes them.
     */
    private static byte[] decodeDelayed(Path file, int frameDelay) {
        var out = new ByteArrayOutputStream();
        try (var source = MediaSource.of(Files.readAllBytes(file));
             var demuxer = Containers.openDemuxer(source)) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            var options = Av1DecoderOptions.builder().maxFrameDelay(frameDelay).build();
            try (var decoder = new Av1Decoder(track, options)) {
                java.util.function.Consumer<com.github.auties00.smalt.video.VideoFrame> sink = frame -> {
                    var picture = (YuvPicture) frame.picture();
                    write(out, picture.y(), picture.yStride(), picture.width(), picture.height());
                    if (picture.u() != null) {
                        write(out, picture.u(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight());
                        write(out, picture.v(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight());
                    }
                };
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    decoder.decode(packet, sink);
                }
                decoder.flush(sink);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return out.toByteArray();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("highBitDepth")
    @DisplayName("10-bit and 12-bit libaom streams decode exactly like ffmpeg on the 16-bit planes")
    void highBitDepth(Case c) {
        var file = Fixtures.ffmpeg(c.extension(), c.input(), c.output());
        var expected = Oracle.decodeVideo(file, c.pixelFormat(), 0);
        assertSame(expected, decodeHighBitDepth(file), c.name());
    }

    @Nested
    @DisplayName("streams of the other AV1 encoders, rav1e and SVT-AV1")
    class OtherEncoders {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.av1.Av1DifferentialTest#otherEncoders")
        @DisplayName("streams decode exactly like ffmpeg")
        void stream(Case c) {
            var encoder = c.output().get(1);
            var ffmpeg = findFfmpegWith(encoder)
                    .orElseThrow(() -> Oracle.missingFeature("no ffmpeg with " + encoder + " on the PATH"));
            var file = encodeWith(ffmpeg, c);
            var tenBit = c.output().contains("yuv420p10le");
            var expected = Oracle.decodeVideo(file, tenBit ? "yuv420p10le" : "yuv420p", 0);
            assertSame(expected, tenBit ? decodeHighBitDepth(file) : decodePublic(file), c.name());
            if (c.name().contains("super-resolution")) {
                assertTrue(usesSuperResolution(file), "the stream does not use super-resolution");
            }
        }
    }

    private static boolean usesSuperResolution(Path file) {
        try (var source = MediaSource.of(Files.readAllBytes(file));
             var demuxer = Containers.openDemuxer(source)) {
            var context = new Av1Context(MediaLimits.DEFAULT);
            var pictures = new ArrayList<Av1Picture>();
            var found = false;
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                pictures.clear();
                context.decode(packet.data(), 0, packet.data().length, 0, 0, pictures);
                for (var p : pictures) {
                    found |= p.frameHdr.superResEnabled;
                    p.release();
                }
            }
            return found;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Optional<Path> findFfmpegWith(String encoder) {
        var path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        for (var dir : path.split(java.io.File.pathSeparator)) {
            for (var name : List.of("ffmpeg.exe", "ffmpeg")) {
                try {
                    var candidate = Path.of(dir.trim(), name);
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)
                            && run(candidate, List.of("-hide_banner", "-encoders")).contains(encoder)) {
                        return Optional.of(candidate);
                    }
                } catch (RuntimeException ignored) {
                    // unusable PATH entry
                }
            }
        }
        return Optional.empty();
    }

    private static String run(Path ffmpeg, List<String> arguments) {
        try {
            var command = new ArrayList<String>();
            command.add(ffmpeg.toString());
            command.addAll(arguments);
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var output = process.getInputStream().readAllBytes();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("ffmpeg timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("ffmpeg failed: " + new String(output, StandardCharsets.UTF_8));
            }
            return new String(output, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static Path encodeWith(Path ffmpeg, Case c) {
        try {
            var key = String.join(" ", c.input()) + "|" + String.join(" ", c.output());
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)), 0, 12);
            var dir = Path.of("target", "media-fixtures");
            Files.createDirectories(dir);
            var target = dir.resolve("av1-" + hash + "." + c.extension());
            if (Files.isRegularFile(target)) {
                return target;
            }
            var temporary = Files.createTempFile(dir, "av1-", "." + c.extension());
            var arguments = new ArrayList<>(List.of("-v", "error", "-y"));
            arguments.addAll(c.input());
            arguments.addAll(c.output());
            arguments.add(temporary.toString());
            run(ffmpeg, arguments);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
