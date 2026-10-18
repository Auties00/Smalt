package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the real-time VP9 encoder's bitstream: each configuration encodes a fixed input and the
 * SHA-256 of its packets (and of the frames it drops) must equal a recorded digest. Any change to
 * the encoder's decisions, coefficients or entropy coding shows up here, so a speed optimization
 * that claims identical output is checked byte for byte. The digests hold for the Vector API
 * kernels and for their scalar twins alike: the suite's scalar leg ({@code -Dsmalt.test.simd=false})
 * runs the same digests. Natural-clip configurations skip when the Xiph clips are not cached.
 */
@DisplayName("real-time VP9 encoder golden bitstreams")
class Vp9EncoderGoldenTest {
    /**
     * A change applied before encoding a given frame.
     *
     * @param frame   the frame index
     * @param bitrate the new target bitrate, or {@code 0} for none
     * @param key     whether that frame is forced to be a key frame
     */
    record Event(int frame, long bitrate, boolean key) {
    }

    static Vp9Setup setup(int width, int height, int bitrate, int speed, boolean errorResilient, boolean aq,
                          int layers, boolean drop, int tiles, int threads) {
        return Vp9Setup.of(width, height, new Rational(30, 1), new Rational(1, 30), bitrate, 2, 52, 0, speed,
                errorResilient, aq, layers, drop, 1000, 500, 600, tiles, threads);
    }

    static Stream<Arguments> synthetic() {
        return Stream.of(
                Arguments.of("speed 6", setup(352, 288, 300_000, 6, false, true, 1, false, 0, 1), List.of(),
                        "23aadb0e3a2c8c4691908c09d918b3879fe69137e8a1038a41d8496a22b696c6"),
                Arguments.of("speed 5", setup(352, 288, 300_000, 5, false, true, 1, false, 0, 1), List.of(),
                        "ca8ce58c3f453e7aef2f9f2ba0c40261d2658666fb6331e12b8d3c52e2ddd67d"),
                Arguments.of("speed 7", setup(352, 288, 300_000, 7, false, true, 1, false, 0, 1), List.of(),
                        "c8cdcbb77e581bfb0e6863c53905b1bdadc46ac656dfd5e41e5c9ce709a30a9c"),
                Arguments.of("speed 8", setup(352, 288, 300_000, 8, false, true, 1, false, 0, 1), List.of(),
                        "fcf1c47b43ff488d7229943952fc990242cffc54e85ad240f035aa75307256dd"),
                Arguments.of("speed 9", setup(352, 288, 300_000, 9, false, true, 1, false, 0, 1), List.of(),
                        "7fca3e8c89a2917a79b8d6ed86e28f2962ad84d7a7452e0f979b070242c3e08b"),
                Arguments.of("error resilient, three layers", setup(352, 288, 300_000, 6, true, true, 3, false, 0, 1),
                        List.of(), "f23332d913268e4e9af691048bd4095a909d58c76d9397385d02112ee3399d54"),
                Arguments.of("two layers", setup(352, 288, 300_000, 7, true, true, 2, false, 0, 1), List.of(),
                        "cc4ec1bbfd332f95cf1d4f52a27d840e8ec339795fc9dc90eed969e6a51e57d3"),
                Arguments.of("no adaptive quantization", setup(352, 288, 300_000, 6, true, false, 1, false, 0, 1),
                        List.of(), "907161e8b9edbd7ef53b3212a8609c2dea77fe7baa1bf7a8b69e8f1f0885f6d9"),
                Arguments.of("two tile columns, three threads", setup(704, 288, 600_000, 6, true, true, 1, false, 1, 3),
                        List.of(), "270db20e7e90f0879b65a8b4424f3ab98f522616d0756df463d369fbe10545ca"),
                Arguments.of("bitrate steps and a forced key frame",
                        setup(352, 288, 300_000, 6, true, true, 1, false, 0, 2),
                        List.of(new Event(8, 900_000, false), new Event(16, 0, true), new Event(20, 120_000, false)),
                        "90142bff8650ba871bd798423b73e3e67da5a55b061a8a94861f47d868b74c86"),
                Arguments.of("dropping at a low bitrate", setup(352, 288, 40_000, 6, true, true, 1, true, 0, 1),
                        List.of(), "3e5c061b1ab0909ea0ff73deb2d97ac2b7ca1861cbf854d788be21e2425630a9"),
                Arguments.of("high bitrate", setup(352, 288, 4_000_000, 6, false, true, 1, false, 0, 1), List.of(),
                        "39af62a8d02b445e6e264b9fb11d47481d390fffd10d612353f2c95a876d0b8f"));
    }

    static Stream<Arguments> natural() {
        return Stream.of(
                Arguments.of("johnny480 speed 6, one thread", "johnny480.yuv",
                        setup(640, 480, 600_000, 6, true, true, 1, false, 0, 1),
                        "d342d3c59bc92c0b96b07c97ccfe07ac40724fe5b53453ce0be191edd85fb487"),
                Arguments.of("johnny480 speed 6, four threads", "johnny480.yuv",
                        setup(640, 480, 600_000, 6, true, true, 1, false, 0, 4),
                        "d342d3c59bc92c0b96b07c97ccfe07ac40724fe5b53453ce0be191edd85fb487"),
                Arguments.of("johnny480 speed 7, two layers", "johnny480.yuv",
                        setup(640, 480, 600_000, 7, true, true, 2, false, 0, 2),
                        "cae1dfb4f077e38da68988ee6bc4f86ebb6cada73ab352838e81e96085eaa99c"),
                Arguments.of("johnny480 speed 8", "johnny480.yuv",
                        setup(640, 480, 600_000, 8, false, true, 1, false, 0, 1),
                        "f7693c2e1f16cf9b68a6220f0668a804b86fe0ebaeab48c7869e0f137fd1b977"),
                Arguments.of("stockholm720 speed 6, four threads, two tiles", "stockholm720.yuv",
                        setup(1280, 720, 1_200_000, 6, true, true, 1, false, 1, 4),
                        "83449d231b6a7a0c867a3f3893aa108cfa62da24f31acf1def4bbfc113ef831d"));
    }

    static String digest(Vp9Setup setup, List<YuvPicture> frames, List<Event> events) {
        var sha = sha256();
        try (var encoder = setup.encoder()) {
            for (var i = 0; i < frames.size(); i++) {
                var key = false;
                for (var event : events) {
                    if (event.frame() == i) {
                        if (event.bitrate() > 0) {
                            encoder.setTargetBitrate(event.bitrate());
                        }
                        key |= event.key();
                    }
                }
                if (key) {
                    encoder.requestKeyframe();
                }
                var packet = encoder.encodeFrame(new VideoFrame(frames.get(i), i, 1, false)).orElse(null);
                if (packet == null) {
                    sha.update(ByteBuffer.allocate(4).putInt(-1).array());
                    continue;
                }
                sha.update(ByteBuffer.allocate(5).putInt(packet.size()).put((byte) (packet.keyframe() ? 1 : 0))
                        .array());
                sha.update(packet.data());
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("synthetic")
    @DisplayName("synthetic motion encodes to the recorded bitstream")
    void synthetic(String name, Vp9Setup setup, List<Event> events, String expected) {
        var frames = Vp9TestVideo.synthesize(setup.width(), setup.height(), 30);
        assertEquals(expected, digest(setup, frames, events), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("natural")
    @DisplayName("a natural clip encodes to the recorded bitstream")
    void natural(String name, String clip, Vp9Setup setup, String expected) throws IOException {
        var path = Vp9TestVideo.clip(clip);
        assumeTrue(path != null, "clip " + clip + " is not cached");
        var frames = Vp9TestVideo.read(path, setup.width(), setup.height(), 0, 40);
        assumeTrue(frames.size() == 40, "clip " + clip + " is too short");
        assertEquals(expected, digest(setup, frames, List.of()), name);
    }
}
