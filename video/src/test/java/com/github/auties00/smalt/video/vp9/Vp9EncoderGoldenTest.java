package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.testkit.Skip;
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
                        "1503647c53920205cacde59f17a76b6a15665f07fcee42fccda4facb5558a517"),
                Arguments.of("speed 5", setup(352, 288, 300_000, 5, false, true, 1, false, 0, 1), List.of(),
                        "be2a9f21f95ffc80796e306922225a4cd08c399fc631d8f99cb3d295e21361cb"),
                Arguments.of("speed 7", setup(352, 288, 300_000, 7, false, true, 1, false, 0, 1), List.of(),
                        "417786b65fa80f850715ddfa7ba3effc1bbb38714d07a3a0ce8b1a44dab8f3ab"),
                Arguments.of("speed 8", setup(352, 288, 300_000, 8, false, true, 1, false, 0, 1), List.of(),
                        "863850b7d5659367d64e0a8bf3ed5b855cb07cb1b41ac8d8ac6bc4d5c3a18e89"),
                Arguments.of("speed 9", setup(352, 288, 300_000, 9, false, true, 1, false, 0, 1), List.of(),
                        "dd197f5d5f5ce668462d6d3fd5fade0f5f312478cc3317e5bfae6c2aec384c8f"),
                Arguments.of("error resilient, three layers", setup(352, 288, 300_000, 6, true, true, 3, false, 0, 1),
                        List.of(), "b1e64a8e46e7ad3542f30dafc7334c5e0fac2c951a5b7354b5494abb4c7e7809"),
                Arguments.of("two layers", setup(352, 288, 300_000, 7, true, true, 2, false, 0, 1), List.of(),
                        "c69007884357004d2a8a9fb734dfb203017d6b83088cc70251c8cfe0da5d72f1"),
                Arguments.of("no adaptive quantization", setup(352, 288, 300_000, 6, true, false, 1, false, 0, 1),
                        List.of(), "cc69139206de7c1b708f70a4f1efcf16abaa229546317a99086d656f91b5bf82"),
                Arguments.of("two tile columns, three threads", setup(704, 288, 600_000, 6, true, true, 1, false, 1, 3),
                        List.of(), "8ee7a68f68a1a389fb52386b6fe6b30f727ad6ff9e471e052f2893a7630b3f61"),
                Arguments.of("bitrate steps and a forced key frame",
                        setup(352, 288, 300_000, 6, true, true, 1, false, 0, 2),
                        List.of(new Event(8, 900_000, false), new Event(16, 0, true), new Event(20, 120_000, false)),
                        "dc9bb2b1616804e3004dadb436ecc7ee782e66693cd2c3690210a03e4600ca12"),
                Arguments.of("dropping at a low bitrate", setup(352, 288, 40_000, 6, true, true, 1, true, 0, 1),
                        List.of(), "04213d4e395ea67eb71b1da3cf969b2df1c8fb2efa743744c5c7d998f8cb2950"),
                Arguments.of("high bitrate", setup(352, 288, 4_000_000, 6, false, true, 1, false, 0, 1), List.of(),
                        "463cb3bac7b7cc1bbf5f8f846cc200e421db81242e650101786a0cd5414904ad"));
    }

    static Stream<Arguments> natural() {
        return Stream.of(
                Arguments.of("johnny480 speed 6, one thread", "johnny480.yuv",
                        setup(640, 480, 600_000, 6, true, true, 1, false, 0, 1),
                        "24ae8a9816e6f509d5944aa370120074e248cf5eecb194015ce3cf47f775afb8"),
                Arguments.of("johnny480 speed 6, four threads", "johnny480.yuv",
                        setup(640, 480, 600_000, 6, true, true, 1, false, 0, 4),
                        "24ae8a9816e6f509d5944aa370120074e248cf5eecb194015ce3cf47f775afb8"),
                Arguments.of("johnny480 speed 7, two layers", "johnny480.yuv",
                        setup(640, 480, 600_000, 7, true, true, 2, false, 0, 2),
                        "afbfe4009f67d0d9f17a4655a45782e5cce5944a809a21ddb0e3c0463f91d723"),
                Arguments.of("johnny480 speed 8", "johnny480.yuv",
                        setup(640, 480, 600_000, 8, false, true, 1, false, 0, 1),
                        "1796a656af487caf394a28b3cccf983f83bdb7ddd1e8ba86adcac556ba4c4383"),
                Arguments.of("stockholm720 speed 6, four threads, two tiles", "stockholm720.yuv",
                        setup(1280, 720, 1_200_000, 6, true, true, 1, false, 1, 4),
                        "11200cba8e13529bc8601bd01ff9cc024dfff0461c59892e0f6ff14621769d0e"));
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
        Skip.unless(path != null, "data:clip", "clip " + clip + " is not cached");
        var frames = Vp9TestVideo.read(path, setup.width(), setup.height(), 0, 40);
        Skip.unless(frames.size() == 40, "data:clip", "clip " + clip + " is too short");
        assertEquals(expected, digest(setup, frames, List.of()), name);
    }
}
