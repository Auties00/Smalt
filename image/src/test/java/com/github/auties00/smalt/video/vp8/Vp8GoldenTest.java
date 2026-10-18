package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.webp.WebpDecoder;
import com.github.auties00.smalt.image.webp.WebpEncoder;
import com.github.auties00.smalt.image.webp.WebpEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp8.internal.Vp8;
import com.github.auties00.smalt.video.vp8.internal.Vp8KeyFrameSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the VP8 encoders' bitstreams and the decoders' pictures: each configuration encodes, or
 * encodes and decodes, a fixed input and the SHA-256 of the result must equal a recorded digest, so
 * a kernel rewrite that claims identical output is checked byte for byte. The digests hold for the
 * Vector API kernels and for their scalar twins alike: the suite's scalar leg
 * ({@code -Dsmalt.test.simd=false}) runs the same digests. The configurations cover the real-time
 * encoder and the video decoder of calls, and the key-frame encoder and decoder of lossy WebP
 * images.
 */
@DisplayName("VP8 golden bitstreams and pictures")
class Vp8GoldenTest {
    private static final Rational FPS = new Rational(30, 1);

    static Stream<Arguments> realtime() {
        return Stream.of(
                Arguments.of("320x240, one thread", Vp8Setup.realtime(320, 240, FPS, 350_000), 30, false,
                        "ca197c4d22655aeda6aad872329b356eb6b31841c4f7dfdaacf2193a54f326db", "1677ee15b03a0a8b0cd9dd0342cdb8ed4c4489a9aaa4d8cb39a19332058e9822"),
                Arguments.of("640x480, four threads",
                        new Vp8Setup(640, 480, Vp8Setup.realtime(640, 480, FPS, 900_000).options().toBuilder()
                                .threads(4).build()), 20,
                        false, "ac20eb691c15e8edbb8cfed04d4261b916449be0f7e83d3721f1403989496124", "6397020892275059b84450d97dc2701f856b5e1e3c144af6324adf5c6d3981c0"),
                Arguments.of("480x360, speed 8, error resilient, bitrate steps and a forced key frame",
                        Vp8Setup.of(480, 360, FPS, new Rational(1, 90_000), 400_000, 4, 56, 0, 8, true, 1, false, 600,
                                400, 500, 1, 2), 36, true, "7600e47e1590c6fd0d17a1ff76f9d3f8fe57a8ab6d2c0bdec6eef7541465f13f", "3b2c8873bb21dd73d6796174bb84d1c27299188a9a9ec4c516e02fdbc9b3670b"),
                Arguments.of("200x120, speed 6, odd macroblock grid",
                        Vp8Setup.of(200, 120, FPS, new Rational(1, 90_000), 150_000, 2, 63, 0, 6, true, 1, false, 600,
                                400, 500, 1, 1), 24, false, "8c7b9867293184b7a53413fecca56c42394130ca0a74a87a134c4a847668a84a", "7b086da53b1bf6086f4d135b8b865e53e0124d4ccc5594d97891233d925874c6"));
    }

    static Stream<Arguments> keyFrames() {
        return Stream.of(
                Arguments.of("q75 m4", new Vp8KeyFrameSettings(75, 4, 4, 60, 0, 1, 50), "92d5771db91af2cad3888c23386bf30b75e212c577364c4f740fce145be30ba0"),
                Arguments.of("q20 m0", new Vp8KeyFrameSettings(20, 0, 4, 40, 0, 1, 50), "b1e663f0064e6de61bc9454c118535e82dbafb0250ba3845413bfa99e9c81598"),
                Arguments.of("q50 m2, two segments", new Vp8KeyFrameSettings(50, 2, 2, 20, 3, 2, 80), "42c052ffb3507a1cb74d393929731587dd8e7c4d481b0b90382319e471f15a9f"),
                Arguments.of("q90 m6", new Vp8KeyFrameSettings(90, 6, 4, 60, 0, 1, 50), "cb799e0d8e808eb69211267cb9c9a68a8e4265ff053dea080044d4d964955f39"),
                Arguments.of("q35 m5, no sns", new Vp8KeyFrameSettings(35, 5, 4, 0, 0, 1, 0), "ff8318ef7fa3ed7e929a48d4117818761031ba1ca3d5612350064a6f3e1af734"));
    }

    static Stream<Arguments> webp() {
        return Stream.of(
                Arguments.of("lossy q75", WebpEncoderOptions.lossy(75), "d587e3fa18790d4f46d3c9d576b17449239c9d948893bf20b6af844d3ac12d15"),
                Arguments.of("lossy q20", WebpEncoderOptions.lossy(20), "909239a0358c3c57c40059b48f50481f108426185900b0756feb2965a78a0dfd"),
                Arguments.of("lossy q95 method 6", WebpEncoderOptions.lossy(95).toBuilder().method(6).build(), "382e7baa1d4bd08757e07e1c4f2505cb5074a0a3f029eb0b886965a2ffcb683e"),
                Arguments.of("lossy q50 method 0", WebpEncoderOptions.lossy(50).toBuilder().method(0).build(), "c3d34ee120794a376f9727efc3a24fa16812d0209e9e785463d88595e0fb42de"));
    }

    @Nested
    @DisplayName("real-time encoder and video decoder")
    class Realtime {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.vp8.Vp8GoldenTest#realtime")
        @DisplayName("produce exactly the recorded stream and pictures")
        void unchanged(String name, Vp8Setup setup, int frames, boolean events, String stream, String pictures) {
            var source = RtTestVideo.synthesize(setup.width(), setup.height(), frames);
            assertDigest(name + " stream", stream, () -> digestPackets(encode(setup, source, events)));
            var packets = encode(setup, source, events);
            assertDigest(name + " pictures", pictures, () -> decode(setup, packets));
        }
    }

    @Nested
    @DisplayName("key-frame encoder")
    class KeyFrames {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.vp8.Vp8GoldenTest#keyFrames")
        @DisplayName("produces exactly the recorded frame")
        void unchanged(String name, Vp8KeyFrameSettings settings, String expected) {
            var picture = RtTestVideo.synthesize(352, 288, 3).get(2);
            assertDigest(name, expected, () -> {
                var sha = sha256();
                sha.update(Vp8.encodeKeyFrame(picture, settings));
                return HexFormat.of().formatHex(sha.digest());
            });
        }
    }

    @Nested
    @DisplayName("lossy WebP")
    class Webp {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.vp8.Vp8GoldenTest#webp")
        @DisplayName("encodes and decodes exactly the recorded bytes")
        void unchanged(String name, WebpEncoderOptions options, String expected) {
            var sticker = sticker(320);
            assertDigest(name, expected, () -> {
                var sha = sha256();
                var data = WebpEncoder.encode(sticker, options);
                sha.update(data);
                update(sha, WebpDecoder.decode(MediaSource.of(data), ImageDecoderOptions.defaults()).picture());
                return HexFormat.of().formatHex(sha.digest());
            });
        }
    }

    private static void assertDigest(String name, String expected, Supplier<String> run) {
        assertEquals(expected, run.get(), name);
    }

    private static List<Packet> encode(Vp8Setup setup, List<YuvPicture> source, boolean events) {
        var packets = new ArrayList<Packet>();
        try (var encoder = setup.encoder()) {
            var step = Math.max(1, setup.frameRate().invert().rescale(1, setup.timeBase()));
            for (var i = 0; i < source.size(); i++) {
                if (events && i == 10) {
                    encoder.setTargetBitrate(900_000);
                }
                if (events && i == 18) {
                    encoder.requestKeyframe();
                }
                if (events && i == 24) {
                    encoder.setTargetBitrate(120_000);
                }
                encoder.encode(new VideoFrame(source.get(i), i * step, step, false), packets::add);
            }
            encoder.flush(packets::add);
        }
        return packets;
    }

    private static String digestPackets(List<Packet> packets) {
        var sha = sha256();
        for (var packet : packets) {
            sha.update(packet.data());
            sha.update(ByteBuffer.allocate(25).putLong(packet.pts()).putLong(packet.dts()).putLong(packet.duration())
                    .put((byte) (packet.keyframe() ? 1 : 0)).array());
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static String decode(Vp8Setup setup, List<Packet> packets) {
        var sha = sha256();
        try (var decoder = new Vp8Decoder(Vp8VideoEncoderTest.track(setup.width(), setup.height()))) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> update(sha, frame.picture()));
            }
            decoder.flush(frame -> update(sha, frame.picture()));
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static RgbaPicture sticker(int size) {
        var picture = RtTestVideo.synthesize(size, size, 2).get(1);
        var half = size / 2;
        var pixels = new byte[size * size * 4];
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                var luma = picture.y()[y * picture.yStride() + x] & 0xFF;
                var o = (y * size + x) * 4;
                pixels[o] = (byte) luma;
                pixels[o + 1] = (byte) (255 - luma);
                pixels[o + 2] = (byte) (luma ^ x);
                pixels[o + 3] = (byte) Math.clamp((long) ((half - 8 - Math.hypot(x - half, y - half)) * 4), 0, 255);
            }
        }
        return RgbaPicture.of(size, size, size * 4, pixels, true);
    }

    private static void update(MessageDigest sha, Picture picture) {
        switch (picture) {
            case RgbaPicture rgba -> {
                for (var y = 0; y < rgba.height(); y++) {
                    sha.update(rgba.pixels(), y * rgba.stride(), 4 * rgba.width());
                }
            }
            case YuvPicture yuv -> {
                for (var y = 0; y < yuv.height(); y++) {
                    sha.update(yuv.y(), y * yuv.yStride(), yuv.width());
                }
                for (var y = 0; y < yuv.chromaHeight(); y++) {
                    sha.update(yuv.u(), y * yuv.uvStride(), yuv.chromaWidth());
                    sha.update(yuv.v(), y * yuv.uvStride(), yuv.chromaWidth());
                }
                if (yuv.alpha() != null) {
                    for (var y = 0; y < yuv.height(); y++) {
                        sha.update(yuv.alpha(), y * yuv.alphaStride(), yuv.width());
                    }
                }
            }
            default -> throw new AssertionError("VP8 returns 8-bit pictures: " + picture);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
