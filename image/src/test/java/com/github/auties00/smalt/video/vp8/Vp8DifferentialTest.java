package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes VP8 streams produced by ffmpeg's libvpx encoder with many settings and compares every
 * shown frame bit-exactly with ffmpeg's decode through libvpx and through its native VP8 decoder.
 */
@DisplayName("VP8 decoder differential against ffmpeg")
class Vp8DifferentialTest {
    static final String TESTSRC = "testsrc2=size=%dx%d:rate=30:duration=%s";

    static Path encode(int width, int height, String seconds, String source, List<String> options) {
        var input = List.of("-f", "lavfi", "-i", source == null ? TESTSRC.formatted(width, height, seconds)
                : source.formatted(width, height, seconds));
        var output = new ArrayList<>(List.of("-c:v", "libvpx"));
        output.addAll(options);
        output.addAll(List.of("-f", "ivf"));
        return Fixtures.ffmpeg("ivf", input, output);
    }

    static Path encodeTwoPass(int width, int height, String seconds, List<String> options) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var key = width + "x" + height + ":" + seconds + ":" + String.join(" ", options);
        try {
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)), 0, 12);
            var dir = Path.of("target", "media-fixtures");
            Files.createDirectories(dir);
            var target = dir.resolve("vp8-2pass-" + hash + ".ivf");
            if (Files.isRegularFile(target)) {
                return target;
            }
            var log = dir.resolve("vp8-2pass-" + hash).toAbsolutePath().toString();
            var common = new ArrayList<>(List.of("-v", "error", "-y", "-f", "lavfi", "-i",
                    TESTSRC.formatted(width, height, seconds), "-c:v", "libvpx"));
            common.addAll(options);
            var pass1 = new ArrayList<>(common);
            pass1.addAll(List.of("-pass", "1", "-passlogfile", log, "-f", "null", "-"));
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), pass1);
            var temporary = Files.createTempFile(dir, "vp8-2pass-", ".ivf");
            var pass2 = new ArrayList<>(common);
            pass2.addAll(List.of("-pass", "2", "-passlogfile", log, "-f", "ivf", temporary.toString()));
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), pass2);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static byte[] ffmpegDecode(Path file, String decoder) {
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-c:v", decoder,
                "-i", file.toString(), "-f", "rawvideo", "-pix_fmt", "yuv420p", "-fps_mode", "passthrough", "-"));
    }

    static List<byte[]> decode(List<byte[]> packets, int width, int height) {
        var frames = new ArrayList<byte[]>();
        try (var decoder = new Vp8Decoder(Vp8ConformanceTest.track(width, height))) {
            var pts = 0L;
            for (var packet : packets) {
                decoder.decode(new Packet(1, packet, pts, pts, 1, false), frame -> frames.add(Ivf.i420((YuvPicture) frame.picture())));
                pts++;
            }
        }
        return frames;
    }

    static void assertSameFrames(byte[] expected, List<byte[]> actual, String label) {
        var out = new ByteArrayOutputStream();
        actual.forEach(out::writeBytes);
        var bytes = out.toByteArray();
        if (expected.length != bytes.length) {
            fail(label + ": ffmpeg produced " + expected.length + " bytes, we produced " + bytes.length
                    + " (" + actual.size() + " frames)");
        }
        var frameSize = actual.isEmpty() ? 1 : actual.getFirst().length;
        for (var i = 0; i < bytes.length; i++) {
            if (expected[i] != bytes[i]) {
                fail(label + ": first difference in frame " + (i / frameSize) + " at byte " + (i % frameSize));
            }
        }
    }

    static Stream<Arguments> streams() {
        return Stream.of(
                Arguments.of("default 176x144", 176, 144, "2", null, List.of("-b:v", "400k")),
                Arguments.of("high quality", 176, 144, "1", null, List.of("-qmin", "0", "-qmax", "4", "-b:v", "8M")),
                Arguments.of("low quality", 176, 144, "2", null, List.of("-qmin", "50", "-qmax", "63", "-b:v", "20k")),
                Arguments.of("2 partitions", 176, 144, "1", null, List.of("-b:v", "400k", "-slices", "2")),
                Arguments.of("4 partitions", 176, 144, "1", null, List.of("-b:v", "400k", "-slices", "4")),
                Arguments.of("8 partitions", 320, 240, "1", null, List.of("-b:v", "600k", "-slices", "8")),
                Arguments.of("error resilient", 176, 144, "2", null, List.of("-b:v", "300k", "-error-resilient", "1")),
                Arguments.of("odd 33x17", 33, 17, "1", null, List.of("-b:v", "100k")),
                Arguments.of("1x1", 1, 1, "1", "testsrc2=size=16x16:rate=30:duration=%3$s,scale=%1$d:%2$d",
                        List.of("-b:v", "10k")),
                Arguments.of("odd 17x31", 17, 31, "1", "testsrc2=size=64x64:rate=30:duration=%3$s,scale=%1$d:%2$d",
                        List.of("-b:v", "50k")),
                Arguments.of("4097 wide", 4097, 16, "0.3", null, List.of("-b:v", "2M")),
                Arguments.of("profile 1", 176, 144, "1", null, List.of("-b:v", "300k", "-profile:v", "1")),
                Arguments.of("profile 2", 176, 144, "1", null, List.of("-b:v", "300k", "-profile:v", "2")),
                Arguments.of("profile 3", 176, 144, "1", null, List.of("-b:v", "300k", "-profile:v", "3")),
                Arguments.of("sharpness 7", 176, 144, "1", null, List.of("-b:v", "200k", "-sharpness", "7")),
                Arguments.of("frequent key frames", 176, 144, "1", null, List.of("-b:v", "300k", "-g", "4")),
                Arguments.of("realtime", 320, 240, "2", null, List.of("-b:v", "500k", "-deadline", "realtime", "-cpu-used", "8")),
                Arguments.of("best quality mode", 176, 144, "1", null, List.of("-b:v", "300k", "-deadline", "best")),
                Arguments.of("mandelbrot", 320, 240, "2", "mandelbrot=size=%dx%d:rate=30:end_scale=0.01,trim=duration=%s",
                        List.of("-b:v", "800k")),
                Arguments.of("cellauto", 160, 120, "2", "cellauto=size=%dx%d:rate=30:rule=110,trim=duration=%s,format=yuv420p",
                        List.of("-b:v", "300k"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("matches ffmpeg's libvpx and native decoders")
    void matches(String name, int width, int height, String seconds, String source, List<String> options) throws IOException {
        var file = encode(width, height, seconds, source, options);
        var ivf = Ivf.parse(Files.readAllBytes(file));
        var ours = decode(ivf.frames().stream().map(Ivf.Frame::data).toList(), width, height);
        assertSameFrames(ffmpegDecode(file, "libvpx"), ours, name + " vs libvpx");
        assertSameFrames(ffmpegDecode(file, "vp8"), ours, name + " vs ffmpeg vp8");
    }

    @Test
    @DisplayName("two-pass stream with hidden alternate reference frames")
    void altref() throws IOException {
        var file = encodeTwoPass(176, 144, "4", List.of("-auto-alt-ref", "1", "-lag-in-frames", "16", "-b:v", "300k"));
        var ivf = Ivf.parse(Files.readAllBytes(file));
        var hidden = ivf.frames().stream().filter(f -> ((f.data()[0] >> 4) & 1) == 0).count();
        var ours = decode(ivf.frames().stream().map(Ivf.Frame::data).toList(), 176, 144);
        assertEquals(ivf.frames().size() - hidden, ours.size(), "hidden frames produce no output");
        assertSameFrames(ffmpegDecode(file, "libvpx"), ours, "altref vs libvpx");
        assertSameFrames(ffmpegDecode(file, "vp8"), ours, "altref vs ffmpeg vp8");
    }

    @Test
    @DisplayName("resolution change on a key frame")
    void resolutionChange() throws IOException {
        var first = encode(176, 144, "0.5", null, List.of("-b:v", "300k"));
        var second = encode(97, 61, "0.5", null, List.of("-b:v", "200k"));
        var packets = new ArrayList<byte[]>();
        Ivf.parse(Files.readAllBytes(first)).frames().forEach(f -> packets.add(f.data()));
        Ivf.parse(Files.readAllBytes(second)).frames().forEach(f -> packets.add(f.data()));
        var ours = decode(packets, 176, 144);
        var expected = new ByteArrayOutputStream();
        expected.writeBytes(ffmpegDecode(first, "libvpx"));
        expected.writeBytes(ffmpegDecode(second, "libvpx"));
        var out = new ByteArrayOutputStream();
        ours.forEach(out::writeBytes);
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected.toByteArray(), out.toByteArray());
    }
}
