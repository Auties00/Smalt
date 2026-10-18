package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the H.264 encoder's bitstream: each configuration encodes a fixed input and the SHA-256 of
 * its packets must equal a recorded digest, so a kernel rewrite that claims identical output is
 * checked byte for byte. The digests hold for the Vector API kernels and for their scalar twins
 * alike: the suite's scalar leg ({@code -Dsmalt.test.simd=false}) runs the same digests.
 * Natural-clip configurations skip when the Xiph clips are not cached. The file preset configurations
 * look ahead and code B pictures; the positional ones code none.
 */
@DisplayName("H.264 encoder golden bitstreams")
class H264EncoderGoldenTest {
    private static final Rational FPS = new Rational(30, 1);

    /**
     * A change applied before encoding a given frame.
     *
     * @param frame   the frame index
     * @param bitrate the new target bitrate, or {@code 0} for none
     * @param key     whether a key frame is requested before that frame
     */
    record Event(int frame, long bitrate, boolean key) {
    }

    static Stream<Arguments> realtime() {
        return Stream.of(
                Arguments.of("320x240, one thread", H264Setup.realtime(320, 240, FPS, 350_000).withThreads(1), 30,
                        List.of(), "2a6e0767a116becb6c31924f632bfc24bfe8c80db4de26828fcb51d96644d5f8"),
                Arguments.of("640x480, four threads", H264Setup.realtime(640, 480, FPS, 900_000).withThreads(4), 20,
                        List.of(), "d6d2a0acf528d787f6eb6caddd51126e5fe3282a201820a2dd6673a3c2b5dbbd"),
                Arguments.of("480x360, bitrate steps and a forced key frame",
                        H264Setup.realtime(480, 360, FPS, 350_000).withThreads(2), 36,
                        List.of(new Event(10, 1_020_000, false), new Event(18, 0, true), new Event(24, 128_000, false)),
                        "ae35722b2894a410daa40a74827cd9c9354abf81c02eb2fa51566c5101710190"),
                Arguments.of("200x120, odd macroblock grid", H264Setup.realtime(200, 120, FPS, 150_000).withThreads(1),
                        24, List.of(), "9d384a9790235a586a490b3f4a87667b84a8db8eadacb0b6aa2d8609ac39451c"));
    }

    static Stream<Arguments> file() {
        return Stream.of(
                Arguments.of("file preset with B-frames", H264Setup.file(320, 240, FPS, 600_000), 24,
                        "d5f5ada58d4b6174711f0648a9eaa32167dbced2c20885a8db278ef47fd71d63"),
                Arguments.of("high profile, slow, constant quality",
                        EncoderHarness.config(352, 288, H264Profile.HIGH, H264EncoderSpeed.SLOW,
                                H264RateControl.CONSTANT_QUALITY, 0, 24, 1), 12, "a4757d2874f1468d369d79dce81b942f06a79c8810feee04e8d07ae02d2292ea"),
                Arguments.of("constrained baseline, fastest, four threads",
                        EncoderHarness.config(640, 480, H264Profile.CONSTRAINED_BASELINE, H264EncoderSpeed.FASTEST,
                                H264RateControl.AVERAGE_BITRATE, 800_000, 26, 4), 12, "2cb10fb326809561fb96a32c3beac517146ec73e811c5992f2450f4c89f470fe"));
    }

    static Stream<Arguments> natural() {
        return Stream.of(
                Arguments.of("johnny480, real time, one thread", "johnny480.yuv", 640, 480,
                        H264Setup.realtime(640, 480, FPS, 600_000).withThreads(1), "c93cba5bc92cc481bd7e59ad27af51bd52046969a96979c9acfb35432a18b460"),
                Arguments.of("stockholm720, real time, four threads", "stockholm720.yuv", 1280, 720,
                        H264Setup.realtime(1280, 720, FPS, 1_500_000).withThreads(4), "761f677319287c382d0583810c42f47dc623d530667fa0364f9703820aca07c6"),
                Arguments.of("foreman, file preset", "foreman.yuv", 352, 288,
                        H264Setup.file(352, 288, FPS, 500_000), "4742ff540b5fc7fdefc103eef12dee66f3011cc85122ac2565434deac965fa10"));
    }

    @Nested
    @DisplayName("real-time configurations")
    class Realtime {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.encoder.H264EncoderGoldenTest#realtime")
        @DisplayName("produce exactly the recorded stream")
        void unchanged(String name, H264Setup setup, int frames, List<Event> events, String expected) {
            IntFunction<YuvPicture> source = i -> EncoderHarness.synthetic(setup.width(), setup.height(), i, 5, 1.0);
            assertEquals(expected, digest(setup, frames, source, events), name);
        }
    }

    @Nested
    @DisplayName("file configurations")
    class File {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.encoder.H264EncoderGoldenTest#file")
        @DisplayName("produce exactly the recorded stream")
        void unchanged(String name, H264Setup setup, int frames, String expected) {
            assertEquals(expected, digest(setup, frames,
                    i -> EncoderHarness.synthetic(setup.width(), setup.height(), i, 11, 2.0), List.of()), name);
        }
    }

    @Nested
    @DisplayName("natural clips")
    class Natural {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.encoder.H264EncoderGoldenTest#natural")
        @DisplayName("produce exactly the recorded stream")
        void unchanged(String name, String clip, int width, int height, H264Setup setup, String expected)
                throws IOException {
            var pictures = clip(clip, width, height, 20);
            assertEquals(expected, digest(setup, pictures.size(), pictures::get, List.of()), name);
        }
    }

    static String digest(H264Setup setup, int frames, IntFunction<YuvPicture> source, List<Event> events) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        try (var encoder = setup.encoder()) {
            encoder.codecConfig().ifPresent(sha::update);
            var pts = 0L;
            var step = Math.max(1, setup.frameRate().invert().rescale(1, setup.timeBase()));
            for (var i = 0; i < frames; i++) {
                for (var event : events) {
                    if (event.frame() == i) {
                        if (event.bitrate() > 0) {
                            encoder.setTargetBitrate(event.bitrate());
                        }
                        if (event.key()) {
                            encoder.requestKeyframe();
                        }
                    }
                }
                encoder.encode(new VideoFrame(source.apply(i), pts, 0, false), packet -> update(sha, packet));
                pts += step;
            }
            encoder.flush(packet -> update(sha, packet));
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static void update(MessageDigest sha, Packet packet) {
        sha.update(packet.data());
        sha.update(ByteBuffer.allocate(25).putLong(packet.pts()).putLong(packet.dts()).putLong(packet.duration())
                .put((byte) (packet.keyframe() ? 1 : 0)).array());
    }

    private static List<YuvPicture> clip(String clip, int width, int height, int frames) throws IOException {
        var root = Path.of(System.getProperty("smalt.test.clips",
                Path.of(System.getProperty("user.home"), ".smalt", "cache", "clips").toString()));
        var file = root.resolve(clip);
        assumeTrue(Files.isRegularFile(file), "clip " + clip + " is not cached");
        var luma = width * height;
        var frameSize = luma * 3 / 2;
        var pictures = new ArrayList<YuvPicture>(frames);
        try (var input = Files.newInputStream(file)) {
            var samples = new byte[frameSize];
            for (var i = 0; i < frames && input.readNBytes(samples, 0, frameSize) == frameSize; i++) {
                pictures.add(YuvPicture.builder()
                        .size(width, height)
                        .chromaFormat(ChromaFormat.YUV420)
                        .color(ColorInfo.BT601_LIMITED)
                        .luma(Arrays.copyOfRange(samples, 0, luma), width)
                        .chroma(Arrays.copyOfRange(samples, luma, luma + luma / 4),
                                Arrays.copyOfRange(samples, luma + luma / 4, frameSize), width / 2)
                        .build());
            }
        }
        assumeTrue(pictures.size() == frames, "clip " + clip + " is shorter than " + frames + " frames");
        return pictures;
    }
}
