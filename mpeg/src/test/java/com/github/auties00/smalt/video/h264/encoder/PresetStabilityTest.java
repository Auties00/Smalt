package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the exact output of the configurations that do not opt into real-time behavior: any change
 * to the message preset (used by lib's media preparation, which looks ahead and codes B pictures)
 * or to a configuration built with the fifteen-component constructor (which codes none) shows up
 * here.
 */
class PresetStabilityTest {
    static Stream<Arguments> presets() {
        var rate = new Rational(30, 1);
        return Stream.of(
                Arguments.of("message", H264Setup.file(320, 240, rate, 600_000), 24, -1,
                        "56af93abb9141631426cfbd563628cec165454dbae8c00a9fd1b0e479ff849b9"),
                Arguments.of("mms-prep", H264Setup.of(256, 144, rate, new Rational(1, 30), H264Profile.MAIN, 0,
                        H264RateControl.AVERAGE_BITRATE, 400_000, 800_000, 800_000, 23, 30, H264EncoderSpeed.BALANCED,
                        ColorInfo.BT709_LIMITED, 2), 30, -1, "381291c40d9eb5b1fded1e18f09fcf4f2fefef266cbdb785ff96548184a51ce3"),
                Arguments.of("cbr-fastest", H264Setup.of(320, 240, rate, new Rational(1, 90_000),
                        H264Profile.CONSTRAINED_BASELINE, 0, H264RateControl.CONSTANT_BITRATE, 350_000, 350_000, 175_000, 26,
                        60, H264EncoderSpeed.FASTEST, ColorInfo.BT709_LIMITED, 1), 40, 7,
                        "a631bc555b73da98e8f80d9844d8f70f1f97706a0e7fe2c4f05b73e73c632d51"),
                Arguments.of("high-cq-slow", H264Setup.of(176, 144, rate, new Rational(1, 30), H264Profile.HIGH, 0,
                        H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 26, 30, H264EncoderSpeed.SLOW, ColorInfo.BT709_LIMITED, 1), 10, -1,
                        "c122a896d0ec7766bd6c94ec18b28f2f1a948b0323ed79f813aec0e1fe184dd5"),
                Arguments.of("main-cq-odd", H264Setup.of(33, 17, rate, new Rational(1, 30), H264Profile.MAIN, 0,
                        H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 30, 30, H264EncoderSpeed.FAST, ColorInfo.BT601_LIMITED, 1), 6, -1,
                        "b08c5afaa479cf36b335d509800687227e320c1486ad1d9d1093bf1ff1b5bd16"),
                Arguments.of("cb-abr-threads", H264Setup.of(160, 96, rate, new Rational(1, 30),
                        H264Profile.CONSTRAINED_BASELINE, 0, H264RateControl.AVERAGE_BITRATE, 150_000, 0, 0, 26, 10, H264EncoderSpeed.FAST,
                        ColorInfo.JPEG, 4), 12, 4, "8c72f0e4e0422b3240c9b882632667d59628acd5376f7e0329fd78b3dec4404a"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("presets")
    @DisplayName("configurations without real-time options produce exactly the recorded stream")
    void unchanged(String name, H264Setup config, int frames, int keyframeAt, String expected) {
        assertEquals(expected, digest(config, frames, keyframeAt), name);
    }

    static String digest(H264Setup config, int frames, int keyframeAt) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        try (var encoder = config.encoder()) {
            sha.update(encoder.codecConfig().orElseThrow());
            var pts = 0L;
            var step = Math.max(1, config.frameRate().invert().rescale(1, config.timeBase()));
            for (var i = 0; i < frames; i++) {
                if (i == keyframeAt) {
                    encoder.requestKeyframe();
                }
                var picture = EncoderHarness.synthetic(config.width(), config.height(), i, 17, 2.0);
                encoder.encode(new VideoFrame(picture, pts, 0, false), packet -> update(sha, packet));
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
}
