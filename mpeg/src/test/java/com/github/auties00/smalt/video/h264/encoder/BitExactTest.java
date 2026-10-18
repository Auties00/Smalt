package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.stream.Stream;

/**
 * Proves normative correctness: ffmpeg's decode of every produced stream must equal the encoder's
 * own reconstruction byte for byte, for every profile, speed, rate control mode, adaptive
 * quantization setting, thread count and a set of awkward picture sizes.
 */
class BitExactTest {
    static Stream<Arguments> matrix() {
        var arguments = new ArrayList<Arguments>();
        for (var profile : H264Profile.values()) {
            for (var speed : H264EncoderSpeed.values()) {
                arguments.add(Arguments.of(profile, speed, 176, 144, 10, 1, 1.0, H264RateControl.CONSTANT_QUALITY, 26));
                arguments.add(Arguments.of(profile, speed, 176, 144, 10, 4, 0.0, H264RateControl.AVERAGE_BITRATE, 26));
                arguments.add(Arguments.of(profile, speed, 33, 17, 6, 1, 1.0, H264RateControl.CONSTANT_BITRATE, 26));
            }
            arguments.add(Arguments.of(profile, H264EncoderSpeed.BALANCED, 18, 18, 5, 1, 1.0, H264RateControl.CONSTANT_QUALITY, 20));
            arguments.add(Arguments.of(profile, H264EncoderSpeed.SLOW, 1, 1, 4, 1, 1.0, H264RateControl.CONSTANT_QUALITY, 30));
            arguments.add(Arguments.of(profile, H264EncoderSpeed.FAST, 96, 64, 6, 1, 0.0, H264RateControl.CONSTANT_QUALITY, 0));
            arguments.add(Arguments.of(profile, H264EncoderSpeed.FAST, 96, 64, 6, 1, 0.0, H264RateControl.CONSTANT_QUALITY, 51));
            arguments.add(Arguments.of(profile, H264EncoderSpeed.FASTEST, 1920, 1080, 3, 4, 1.0, H264RateControl.AVERAGE_BITRATE, 26));
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} {1} {2}x{3}x{4} threads={5} aq={6} {7} q={8}")
    @MethodSource("matrix")
    @DisplayName("ffmpeg decodes the stream to exactly the encoder's reconstruction")
    void bitExact(H264Profile profile, H264EncoderSpeed speed, int width, int height, int frames, int threads, double aq,
                  H264RateControl rc, int quality) {
        var bitrate = Math.max(20_000, width * height * 30 / 8);
        var config = EncoderHarness.config(width, height, profile, speed, rc, bitrate, quality, threads);
        var pictures = EncoderHarness.sequence(width, height, frames, width * 31L + height, 3.0);
        var encoded = EncoderHarness.encode(config, pictures, aq);
        EncoderHarness.assertBitExact("bitexact-" + profile + "-" + speed + "-" + width + "x" + height + "-t" + threads
                + "-aq" + aq + "-" + rc + "-" + quality, encoded);
    }
}
