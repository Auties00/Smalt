package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the AV1 encoder's bitstream: each configuration encodes a fixed input and the SHA-256 of
 * its packets (and of the frames it drops) must equal a recorded digest. Any change to the
 * encoder's decisions, coefficients or entropy coding shows up here, so a speed optimization that
 * claims identical output is checked byte for byte. The digests hold for the Vector API kernels
 * and for their scalar twins alike: the suite's scalar leg ({@code -Dsmalt.test.simd=false}) runs
 * the same digests. Natural-clip configurations skip when the Xiph clips are not cached.
 */
@DisplayName("AV1 encoder golden bitstreams")
class Av1EncoderGoldenTest {
    /**
     * A change applied before encoding a given frame.
     *
     * @param frame   the frame index
     * @param bitrate the new target bitrate, or {@code 0} for none
     * @param key     whether that frame is forced to be a key frame
     */
    record Event(int frame, long bitrate, boolean key) {
    }

    static Av1EncoderOptions realtime(long bitrate, Av1EncoderSpeed speed) {
        return Av1EncoderOptions.forRealtime().toBuilder().bitrate(bitrate).speed(speed).dropFrames(false)
                .timeBase(new Rational(1, 30)).build();
    }

    static Stream<Arguments> synthetic() {
        return Stream.of(
                Arguments.of("faster", 352, 288, realtime(300_000, Av1EncoderSpeed.FASTER), List.of(),
                        "9902c6431aca2bb6f8beff7389cfab4570c8d8bc4de96e0e692dae56d2130c04"),
                Arguments.of("balanced", 352, 288, realtime(300_000, Av1EncoderSpeed.BALANCED), List.of(),
                        "53b7c4ac3099bf81ea2a05eb23e44ab4ad0b5bb2fdae19dd602348930ca6e811"),
                Arguments.of("fast", 352, 288, realtime(300_000, Av1EncoderSpeed.FAST), List.of(),
                        "4db049864a00c177a419b6977f0bab031e10cce3eaa5beb36b064ee34c2fd0bb"),
                Arguments.of("fastest", 352, 288, realtime(300_000, Av1EncoderSpeed.FASTEST), List.of(),
                        "a1f13ecc5b1101e579b0d3b2af065f2fe62ebc37dc8e660b8ad469101efbe152"),
                Arguments.of("three layers", 352, 288,
                        realtime(300_000, Av1EncoderSpeed.FASTER).toBuilder().temporalLayers(3).build(), List.of(),
                        "f6d22bfcdc836a431558ff433b4b5f8e34153d1142b37c97f26deb674a94fc81"),
                Arguments.of("two layers, not error resilient", 352, 288,
                        realtime(300_000, Av1EncoderSpeed.FASTER).toBuilder().temporalLayers(2).errorResilient(false)
                                .build(),
                        List.of(), "b8569bdb78907394ea57c27945bd749cb0995764b0d12575ec7b7da499fa4ce4"),
                Arguments.of("no adaptive quantization", 352, 288,
                        realtime(300_000, Av1EncoderSpeed.FASTER).toBuilder().adaptiveQuantization(false).build(), List
                                .of(),
                        "91bb59f632b8747bc79de62f936997573aa4610d54665dce72c8df0bd9319274"),
                Arguments.of("row wavefront, four threads", 704, 288,
                        realtime(600_000, Av1EncoderSpeed.FASTER).toBuilder().threads(4).build(), List.of(),
                        "f56e37d8555411ff9c67e8211f0d03bc08e54bdeb8e745453c55eeb48333de62"),
                Arguments.of("two tile columns, three threads", 704, 288,
                        realtime(600_000, Av1EncoderSpeed.FASTER).toBuilder().threads(3).tileColumnsLog2(1)
                                .build(), List.of(),
                        "d88a4d8a007c3d0c592eea5fa8c1dfed896f900be26880cf1779db66cad829d7"),
                Arguments.of("bitrate steps and a forced key frame", 352, 288,
                        realtime(300_000, Av1EncoderSpeed.FASTER).toBuilder().threads(2).build(),
                        List.of(new Event(8, 900_000, false), new Event(16, 0, true), new Event(20, 120_000, false)),
                        "1d4193b9c1b9e80673467524ff0c58df640ad6b393c0c4e304480c2e8f43bf70"),
                Arguments.of("dropping at a low bitrate", 352, 288,
                        realtime(40_000, Av1EncoderSpeed.FASTER).toBuilder().dropFrames(true).build(), List.of(),
                        "483b2e34796896189eb720224be4ea6e41c20bfaa872bf04fafb5b1aa4e0bd8b"),
                Arguments.of("uncapped key frames, high bitrate", 352, 288,
                        realtime(4_000_000, Av1EncoderSpeed.FASTER).toBuilder().noMaxKeyframeBitrate().build(),
                        List.of(), "1c5f0a481767b7854ae23e21666f4604cf5d0040ba22276058309a08e46bb257"),
                Arguments.of("intra only", 352, 288, Av1EncoderOptions.forIntraOnly(70), List.of(),
                        "6a9c32c5ae202f9294f4d74a88b29b6a9d5f66515770924a3f5cc05db094e277"));
    }

    static Stream<Arguments> natural() {
        return Stream.of(
                Arguments.of("johnny480 faster, one thread", "johnny480.yuv", 640, 480,
                        realtime(600_000, Av1EncoderSpeed.FASTER),
                        "a3acc22d3e5e3b4ff50045e43b0c0d2a4b23d18eb30d8205eb95af1ff7ad21dc"),
                Arguments.of("johnny480 faster, four threads", "johnny480.yuv", 640, 480,
                        realtime(600_000, Av1EncoderSpeed.FASTER).toBuilder().threads(4).build(),
                        "a3acc22d3e5e3b4ff50045e43b0c0d2a4b23d18eb30d8205eb95af1ff7ad21dc"),
                Arguments.of("johnny480 fastest, two layers", "johnny480.yuv", 640, 480,
                        realtime(600_000, Av1EncoderSpeed.FASTEST).toBuilder().temporalLayers(2).threads(2).build(),
                        "92f145b271885d4b0744ac94fbdc97b8c798b0320185481e0be873ba150d29a7"),
                Arguments.of("johnny480 balanced", "johnny480.yuv", 640, 480,
                        realtime(600_000, Av1EncoderSpeed.BALANCED),
                        "a8a914c8b0cc8f0a2921c7ec390cd973db66a8d2bacae4d5809bc19a2c6d9416"),
                Arguments.of("stockholm720 faster, four threads", "stockholm720.yuv", 1280, 720,
                        realtime(1_200_000, Av1EncoderSpeed.FASTER).toBuilder().threads(4).build(),
                        "5a9cdae00355e04919100712ff884356ea69801e2c2b52049c3c164f5ab3863d"));
    }

    static Stream<Arguments> good() {
        return Stream.of(
                Arguments.of("good quality, slow", Av1EncoderOptions.ofQuality(55), 0,
                        "7416e4aeb3699661ad66becba605348f71817a94deea2bf497b8c85bc8678af9"),
                Arguments.of("good quality, slowest, three threads", Av1EncoderOptions.ofQuality(55)
                        .toBuilder()
                        .speed(Av1EncoderSpeed.SLOWEST).threads(3)
                        .build(), 0,
                        "772cfe3facd47e4f80a28fbd3db58ffd891b649e2a5e6c2836381aa3ad53492e"),
                Arguments.of("variable bitrate, fast, two tile columns", Av1EncoderOptions.ofBitrate(400_000)
                        .toBuilder()
                        .speed(Av1EncoderSpeed.FAST).tileColumnsLog2(1).threads(2)
                        .build(), 0,
                        "ea4003bd967e2a485ee9ebc1aca43b12c688b7a4e6e30af3d2d057fd2199497e"),
                Arguments.of("archive, a forced key frame", Av1EncoderOptions.forArchive(), 13,
                        "fa0a9d7556f6a9ac08fe2fff80a8d7b39733b73c0a11b22155e1e4a7d196d5ea"));
    }

    static String goodDigest(int width, int height, Av1EncoderOptions options, List<YuvPicture> frames, int key) {
        var sha = sha256();
        try (var encoder = new Av1Encoder(width, height, options)) {
            Consumer<Packet> output = packet -> {
                sha.update(ByteBuffer.allocate(13).putLong(packet.pts()).putInt(packet.size())
                        .put((byte) (packet.keyframe() ? 1 : 0)).array());
                sha.update(packet.data());
            };
            for (var i = 0; i < frames.size(); i++) {
                if (key > 0 && i == key) {
                    encoder.requestKeyframe();
                }
                encoder.encode(new VideoFrame(frames.get(i), i, 1, false), output);
            }
            encoder.flush(output);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    static String digest(int width, int height, Av1EncoderOptions options, List<YuvPicture> frames,
                         List<Event> events) {
        var sha = sha256();
        try (var encoder = new Av1Encoder(width, height, options)) {
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
    void synthetic(String name, int width, int height, Av1EncoderOptions options, List<Event> events,
                   String expected) {
        var frames = Vp9TestVideo.synthesize(width, height, 24);
        assertEquals(expected, digest(width, height, options, frames, events), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("good")
    @DisplayName("good-quality coding of synthetic motion encodes to the recorded bitstream")
    void good(String name, Av1EncoderOptions options, int key, String expected) {
        var frames = Vp9TestVideo.synthesize(352, 288, 24);
        assertEquals(expected, goodDigest(352, 288, options.toBuilder().timeBase(new Rational(1, 30))
                .build(), frames, key), name);
    }

    @ParameterizedTest(name = "{0} threads")
    @ValueSource(ints = {1, 2, 3, 8})
    @DisplayName("good-quality coding encodes to the same bitstream on any number of threads")
    void goodThreads(int threads) {
        var frames = Vp9TestVideo.synthesize(640, 480, 17);
        var options = Av1EncoderOptions.ofQuality(55).toBuilder().speed(Av1EncoderSpeed.FAST).threads(threads)
                .timeBase(new Rational(1, 30)).build();
        var reference = Av1EncoderOptions.ofQuality(55).toBuilder().speed(Av1EncoderSpeed.FAST).threads(1)
                .timeBase(new Rational(1, 30)).build();
        assertEquals(goodDigest(640, 480, reference, frames, 0), goodDigest(640, 480, options, frames, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("natural")
    @DisplayName("a natural clip encodes to the recorded bitstream")
    void natural(String name, String clip, int width, int height, Av1EncoderOptions options, String expected)
            throws IOException {
        var path = Vp9TestVideo.clip(clip);
        Skip.unless(path != null, "data:clip", "clip " + clip + " is not cached");
        var frames = Vp9TestVideo.read(path, width, height, 0, 30);
        Skip.unless(frames.size() == 30, "data:clip", "clip " + clip + " is too short");
        assertEquals(expected, digest(width, height, options, frames, List.of()), name);
    }
}
