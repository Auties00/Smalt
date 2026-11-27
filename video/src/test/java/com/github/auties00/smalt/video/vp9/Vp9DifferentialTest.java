package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.vp9.internal.Vp9Frame;
import com.github.auties00.smalt.video.vp9.internal.Vp9FrameDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes VP9 streams made by ffmpeg's libvpx-vp9 encoder with many settings (tiles, row
 * multithreading, realtime with error resilience, lossless, frame-parallel mode, adaptive
 * quantization, every profile and bit depth) from natural video (the Xiph {@code foreman} clip),
 * plus the libvpx scaled-reference vectors, and requires
 * every frame to match ffmpeg's decode through libvpx and through its native VP9 decoder bit
 * for bit, single-threaded and with parallel tiles.
 */
@DisplayName("VP9 decoder differential against ffmpeg")
class Vp9DifferentialTest {
    /** VP9 reconstruction is normative, so every shown frame equals libvpx's and ffmpeg's decodes; frame MD5s compare equal. */
    static final Tolerance TOLERANCE = Tolerance.exact();

    static Path encode(String label, int width, int height, List<String> options, int frames) {
        var input = List.of("-i", Conformance.vector("foreman_cif.y4m").toString());
        var output = new ArrayList<>(List.of("-vf", "scale=" + width + ":" + height));
        output.addAll(List.of("-frames:v", Integer.toString(frames), "-c:v", "libvpx-vp9"));
        output.addAll(options);
        output.addAll(List.of("-an", "-f", "webm"));
        return Fixtures.ffmpeg("webm", input, output);
    }

    static List<byte[]> webmPackets(Path file) {
        var packets = new ArrayList<byte[]>();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(file)))) {
            var track = demuxer.tracks().stream().filter(VideoTrack.class::isInstance).findFirst().orElseThrow();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                if (packet.trackId() == track.id()) {
                    packets.add(packet.data());
                }
            }
        }
        return packets;
    }

    static List<String> frameMd5s(List<byte[]> packets, boolean parallel) {
        var md5s = new ArrayList<String>();
        var decoder = new Vp9FrameDecoder(MediaLimits.DEFAULT, parallel);
        for (var packet : packets) {
            decoder.decodePacket(packet, 0, packet.length);
            var frame = decoder.shownFrame();
            md5s.add(frame == null ? null : Vp9Vectors.md5(frame));
        }
        return md5s;
    }

    /** Returns the visible planes of every shown frame, laid out like ffmpeg's rawvideo output. */
    static List<byte[]> decodeRaw(List<byte[]> packets, boolean parallel) {
        var frames = new ArrayList<byte[]>();
        var decoder = new Vp9FrameDecoder(MediaLimits.DEFAULT, parallel);
        for (var packet : packets) {
            decoder.decodePacket(packet, 0, packet.length);
            var frame = decoder.shownFrame();
            if (frame != null) {
                frames.add(raw(frame));
            }
        }
        return frames;
    }

    static byte[] raw(Vp9Frame frame) {
        var out = new ByteArrayOutputStream();
        for (var plane = 0; plane < 3; plane++) {
            var w = frame.cropWidths[plane];
            var h = frame.cropHeights[plane];
            var stride = frame.strides[plane];
            for (var y = 0; y < h; y++) {
                if (frame.highBitDepth()) {
                    for (var x = 0; x < w; x++) {
                        var v = frame.planes16[plane][y * stride + x];
                        out.write(v & 0xFF);
                        out.write((v >> 8) & 0xFF);
                    }
                } else {
                    out.write(frame.planes8[plane], y * stride, w);
                }
            }
        }
        return out.toByteArray();
    }

    static byte[] ffmpegDecode(Path file, String decoder, String pixelFormat) {
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-c:v", decoder,
                "-i", file.toString(), "-f", "rawvideo", "-pix_fmt", pixelFormat, "-fps_mode", "passthrough", "-"));
    }

    static void assertSameFrames(byte[] expected, List<byte[]> actual, String label) {
        var out = new ByteArrayOutputStream();
        actual.forEach(out::writeBytes);
        var bytes = out.toByteArray();
        if (expected.length != bytes.length) {
            fail(label + ": ffmpeg produced " + expected.length + " bytes, we produced " + bytes.length + " ("
                    + actual.size() + " frames)");
        }
        var frameSize = actual.isEmpty() ? 1 : actual.getFirst().length;
        TOLERANCE.assertFrames(label, expected, bytes, frameSize);
    }

    static Stream<Arguments> streams() {
        return Stream.of(
                Arguments.of("good 352x288", 352, 288, "yuv420p", List.of("-b:v", "400k", "-cpu-used", "2")),
                Arguments.of("4 tile columns 2 rows 1280x720", 1280, 720, "yuv420p",
                        List.of("-b:v", "1M", "-tile-columns", "2", "-tile-rows", "1", "-cpu-used", "4")),
                Arguments.of("row-mt 2 tile columns", 704, 576, "yuv420p",
                        List.of("-b:v", "800k", "-row-mt", "1", "-tile-columns", "1", "-cpu-used", "4")),
                Arguments.of("realtime error resilient", 352, 288, "yuv420p", List.of("-b:v", "300k", "-deadline",
                        "realtime", "-cpu-used", "8", "-error-resilient", "1", "-lag-in-frames", "0")),
                Arguments.of("lossless", 176, 144, "yuv420p", List.of("-lossless", "1", "-cpu-used", "4")),
                Arguments.of("frame parallel", 352, 288, "yuv420p", List.of("-b:v", "300k", "-frame-parallel", "1",
                        "-cpu-used", "4")),
                Arguments.of("cyclic aq segmentation", 352, 288, "yuv420p", List.of("-b:v", "300k", "-aq-mode", "3",
                        "-deadline", "realtime", "-cpu-used", "6")),
                Arguments.of("high quality", 352, 288, "yuv420p", List.of("-crf", "4", "-b:v", "0", "-cpu-used", "4")),
                Arguments.of("low quality", 352, 288, "yuv420p", List.of("-crf", "63", "-b:v", "0", "-cpu-used", "4")),
                Arguments.of("sharpness 7", 352, 288, "yuv420p", List.of("-b:v", "200k", "-sharpness", "7",
                        "-cpu-used", "4")),
                Arguments.of("odd 33x17", 33, 17, "yuv420p", List.of("-b:v", "50k")),
                Arguments.of("odd 197x101", 197, 101, "yuv420p", List.of("-b:v", "200k", "-cpu-used", "4")),
                Arguments.of("profile 1 4:4:4", 176, 144, "yuv444p", List.of("-b:v", "300k", "-pix_fmt", "yuv444p",
                        "-cpu-used", "4")),
                Arguments.of("profile 1 4:2:2", 176, 144, "yuv422p", List.of("-b:v", "300k", "-pix_fmt", "yuv422p",
                        "-cpu-used", "4")),
                Arguments.of("profile 1 4:4:0", 176, 144, "yuv440p", List.of("-b:v", "300k", "-pix_fmt", "yuv440p",
                        "-cpu-used", "4")),
                Arguments.of("profile 2 10-bit", 176, 144, "yuv420p10le", List.of("-b:v", "300k", "-pix_fmt",
                        "yuv420p10le", "-cpu-used", "4")),
                Arguments.of("profile 2 12-bit", 176, 144, "yuv420p12le", List.of("-b:v", "300k", "-pix_fmt",
                        "yuv420p12le", "-cpu-used", "4")),
                Arguments.of("profile 3 12-bit 4:4:4", 176, 144, "yuv444p12le", List.of("-b:v", "400k", "-pix_fmt",
                        "yuv444p12le", "-cpu-used", "4")),
                Arguments.of("profile 3 10-bit 4:2:2", 176, 144, "yuv422p10le", List.of("-b:v", "400k", "-pix_fmt",
                        "yuv422p10le", "-cpu-used", "4"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("matches ffmpeg's libvpx and native decoders")
    void matches(String name, int width, int height, String pixelFormat, List<String> options) {
        var file = encode(name, width, height, options, 30);
        var packets = webmPackets(file);
        var ours = decodeRaw(packets, false);
        assertSameFrames(ffmpegDecode(file, "libvpx-vp9", pixelFormat), ours, name + " vs libvpx");
        assertSameFrames(ffmpegDecode(file, "vp9", pixelFormat), ours, name + " vs ffmpeg vp9");
        assertEquals(frameMd5s(packets, false), frameMd5s(packets, true), name + " parallel");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vp90-2-21-resize_inter_320x180_7_1-2.webm", "vp90-2-21-resize_inter_640x360_5_3-4.webm",
            "vp90-2-21-resize_inter_1280x720_7_1-2.webm", "vp90-2-05-resize.ivf"})
    @DisplayName("scaled-reference streams match ffmpeg's libvpx and native decoders frame by frame")
    void scaledReferences(String name) throws Exception {
        var file = Vp9Vectors.stream(name);
        var ours = frameMd5s(Vp9Vectors.packets(name, Files.readAllBytes(file)), false).stream()
                .filter(Objects::nonNull).toList();
        for (var decoder : List.of("vp9", "libvpx-vp9")) {
            assertEquals(ffmpegFrameMd5s(file, decoder, List.of("-noautoscale")), ours, name + " vs " + decoder);
        }
    }

    /**
     * VP9 from pipelines other than ffmpeg's libvpx encodes: libvpx's YouTube-like ladder (alternate reference
     * pyramids; 1x1 tiles, a height that is not a multiple of 8, 1x4 tiles in frame parallel mode) and Chromium's
     * samples (profile 1 4:2:2, profile 2 10-bit, BT.709 signalled in the bitstream and the WebM Colour element, and
     * 4:4:4 RGB in MP4).
     */
    static Stream<Arguments> realWorld() {
        return Stream.of(
                Arguments.of("vp90-2-bbb_426x240_tile_1x1_180kbps.webm", "yuv420p"),
                Arguments.of("vp90-2-sintel_426x182_tile_1x1_171kbps.webm", "yuv420p"),
                Arguments.of("vp90-2-tos_1280x534_tile_1x4_fpm_952kbps.webm", "yuv420p"),
                Arguments.of("chromium-bear-vp9-i422.webm", "yuv422p"),
                Arguments.of("chromium-bear-320x240-vp9_profile2.webm", "yuv420p10le"),
                Arguments.of("chromium-bear-vp9-bt709.webm", "yuv420p"),
                // VP9 codes RGB as G, B, R in its three planes, ffmpeg's gbrp order
                Arguments.of("chromium-gbrp-vp9.mp4", "gbrp"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("realWorld")
    @DisplayName("real-world streams match ffmpeg's libvpx and native decoders")
    void realWorld(String name, String pixelFormat) {
        var file = Conformance.vector(name);
        // the ladder streams run for minutes, too many raw frames to hold: each side is compared through the MD5 of
        // every shown frame's packed planes, ffmpeg's framemd5 of its rawvideo output
        var packets = webmPackets(file);
        var md5s = frameMd5s(packets, false);
        var ours = md5s.stream().filter(Objects::nonNull).toList();
        for (var decoder : List.of("libvpx-vp9", "vp9")) {
            assertEquals(ffmpegFrameMd5s(file, decoder, List.of("-pix_fmt", pixelFormat)), ours,
                    name + " vs " + decoder);
        }
        assertEquals(md5s, frameMd5s(packets, true), name + " parallel");
    }

    /** Returns the per-frame MD5s of ffmpeg's decode of a file through one decoder, in output order. */
    static List<String> ffmpegFrameMd5s(Path file, String decoder, List<String> outputArguments) {
        var arguments = new ArrayList<>(List.of("-v", "error", "-c:v", decoder, "-i", file.toString(), "-fps_mode",
                "passthrough"));
        arguments.addAll(outputArguments);
        arguments.addAll(List.of("-f", "framemd5", "-"));
        var lines = new String(Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), arguments),
                StandardCharsets.US_ASCII).split("\\R");
        var md5s = new ArrayList<String>();
        for (var line : lines) {
            if (!line.isBlank() && !line.startsWith("#")) {
                var fields = line.split(",");
                md5s.add(fields[fields.length - 1].trim());
            }
        }
        return md5s;
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource({"chromium-bear-vp9-bt709.webm,2,false",
            "chromium-gbrp-vp9.mp4,7,true"})
    @DisplayName("reads the color space and range of the uncompressed header (VP9 7.2: CS_BT_709 2, CS_RGB 7)")
    void colorSpace(String name, int colorSpace, boolean fullRange) {
        var decoder = new Vp9FrameDecoder(MediaLimits.DEFAULT, false);
        var frames = 0;
        for (var packet : webmPackets(Conformance.vector(name))) {
            decoder.decodePacket(packet, 0, packet.length);
            var frame = decoder.shownFrame();
            if (frame != null) {
                assertEquals(colorSpace, frame.colorSpace, name + " frame " + frames);
                assertEquals(fullRange, frame.fullRange, name + " frame " + frames);
                frames++;
            }
        }
        assertTrue(frames > 0, name);
    }
}
