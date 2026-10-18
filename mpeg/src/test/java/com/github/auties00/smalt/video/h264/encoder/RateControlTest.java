package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the rate control modes on ten-second synthetic sequences: the average bitrate, the
 * buffering verifier (simulated from packet sizes exactly as documented: a bucket that starts
 * full, loses each frame's bits and refills at the maximum bitrate), the key frame interval and
 * scene cut detection.
 */
class RateControlTest {
    private static final int FPS = 30;

    private static List<Packet> encode(H264Setup config, List<YuvPicture> pictures) {
        return EncoderHarness.encode(config, pictures).packets();
    }

    private static long bits(List<Packet> packets) {
        return packets.stream().mapToLong(Packet::size).sum() * 8;
    }

    /** Returns the lowest bucket fullness seen, negative when the verifier underflowed. */
    private static double simulateBucket(List<Packet> packets, double bufferSize, double maxBitrate, double fps) {
        var fill = bufferSize;
        var lowest = bufferSize;
        for (var packet : packets) {
            fill -= packet.size() * 8.0;
            lowest = Math.min(lowest, fill);
            fill = Math.min(bufferSize, fill + maxBitrate / fps);
        }
        return lowest;
    }

    @ParameterizedTest(name = "{0} at {1} bps, {2}")
    @CsvSource({
            "BALANCED, 400000, 2.0",
            "BALANCED, 150000, 4.0",
            "FAST, 800000, 1.0",
            "FASTEST, 300000, 3.0"
    })
    @DisplayName("average bitrate lands within 5% of the target over ten seconds and never underflows the buffer")
    void averageBitrate(H264EncoderSpeed speed, int bitrate, double noise) {
        var pictures = EncoderHarness.sequence(320, 240, 10 * FPS, 11, noise);
        var config = H264Setup.of(320, 240, new Rational(FPS, 1), new Rational(1, FPS), H264Profile.MAIN, 0,
                H264RateControl.AVERAGE_BITRATE, bitrate, 0, 0, 23, 2 * FPS, speed, ColorInfo.BT709_LIMITED, 1);
        var packets = encode(config, pictures);
        var actual = bits(packets) / 10.0;
        assertTrue(Math.abs(actual - bitrate) <= 0.05 * bitrate,
                "average bitrate " + actual + " vs target " + bitrate);
        assertTrue(simulateBucket(packets, config.effectiveBufferSize(), config.effectiveMaxBitrate(), FPS) >= 0,
                "buffer underflow");
    }

    @Test
    @DisplayName("constant bitrate keeps every frame within the buffer and follows the target rate")
    void constantBitrate() {
        var pictures = EncoderHarness.sequence(320, 240, 10 * FPS, 5, 3.0);
        var config = H264Setup.realtime(320, 240, new Rational(FPS, 1), 250_000);
        var packets = new ArrayList<Packet>();
        try (var encoder = config.encoder()) {
            for (var i = 0; i < pictures.size(); i++) {
                encoder.encode(new com.github.auties00.smalt.video.VideoFrame(pictures.get(i), i * 3000L, 3000,
                        false), packets::add);
            }
        }
        assertTrue(simulateBucket(packets, config.effectiveBufferSize(), config.effectiveMaxBitrate(), FPS) >= 0,
                "buffer underflow");
        var actual = bits(packets) / 10.0;
        assertTrue(actual <= 250_000 * 1.05 && actual >= 250_000 * 0.8, "bitrate " + actual);
    }

    @Test
    @DisplayName("a buffer smaller than a normal intra frame forces the quantizer up instead of underflowing")
    void tightBuffer() {
        var pictures = EncoderHarness.sequence(320, 240, 60, 3, 6.0);
        var config = H264Setup.of(320, 240, new Rational(FPS, 1), new Rational(1, FPS), H264Profile.MAIN, 0,
                H264RateControl.AVERAGE_BITRATE, 300_000, 300_000, 60_000, 23, 30, H264EncoderSpeed.FAST, ColorInfo.BT709_LIMITED, 1);
        var packets = encode(config, pictures);
        assertTrue(simulateBucket(packets, 60_000, 300_000, FPS) >= 0, "buffer underflow");
    }

    @Test
    @DisplayName("IDR frames appear exactly at the configured interval on continuous content")
    void keyframeInterval() {
        var pictures = EncoderHarness.sequence(160, 128, 100, 9, 1.0);
        var config = H264Setup.of(160, 128, new Rational(FPS, 1), new Rational(1, FPS), H264Profile.HIGH, 0,
                H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 28, 25, H264EncoderSpeed.FAST, ColorInfo.BT709_LIMITED, 1);
        var packets = encode(config, pictures);
        for (var i = 0; i < packets.size(); i++) {
            assertEquals(i % 25 == 0, packets.get(i).keyframe(), "keyframe flag of frame " + i);
        }
    }

    @Test
    @DisplayName("a scene cut produces an IDR frame")
    void sceneCut() {
        var pictures = new ArrayList<YuvPicture>();
        pictures.addAll(EncoderHarness.sequence(160, 128, 20, 1, 1.0));
        for (var i = 0; i < 20; i++) {
            pictures.add(EncoderHarness.checkerboard(160, 128, i));
        }
        var config = H264Setup.of(160, 128, new Rational(FPS, 1), new Rational(1, FPS), H264Profile.MAIN, 0,
                H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 28, 0, H264EncoderSpeed.FAST, ColorInfo.BT709_LIMITED, 1);
        var packets = encode(config, pictures);
        assertTrue(packets.get(0).keyframe());
        assertTrue(packets.get(20).keyframe(), "the first frame of the new scene is an IDR");
        var keyframes = packets.stream().filter(Packet::keyframe).count();
        assertEquals(2, keyframes, "only the first frame and the cut are IDR frames");
    }
}
