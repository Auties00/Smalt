package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the AV1 encoder through unusual sizes, content and settings: tiny and odd pictures, flat
 * pictures, pure noise, scene cuts, the quantizer extremes, every speed with every option toggled,
 * and a long run whose rate must settle on the target. Every stream must decode with the module's
 * decoder, frame for frame.
 */
class Av1EncoderRobustnessTest {
    private static List<YuvPicture> noise(int width, int height, int frames, long seed) {
        var random = new Random(seed);
        var out = new ArrayList<YuvPicture>();
        for (var i = 0; i < frames; i++) {
            var p = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            random.nextBytes(p.y());
            random.nextBytes(p.u());
            random.nextBytes(p.v());
            out.add(p);
        }
        return out;
    }

    private static List<YuvPicture> flat(int width, int height, int frames) {
        var out = new ArrayList<YuvPicture>();
        for (var i = 0; i < frames; i++) {
            var p = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            java.util.Arrays.fill(p.y(), (byte) (16 + i));
            java.util.Arrays.fill(p.u(), (byte) 128);
            java.util.Arrays.fill(p.v(), (byte) 128);
            out.add(p);
        }
        return out;
    }

    private static List<Packet> encode(Av1EncoderOptions options, int width, int height, List<YuvPicture> pictures) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new Av1Encoder(width, height, options)) {
            for (var i = 0; i < pictures.size(); i++) {
                encoder.encode(new VideoFrame(pictures.get(i), i, 1, false), packets::add);
            }
            var decoded = decode(encoder, packets, width, height);
            assertEquals(packets.size(), decoded);
        }
        return packets;
    }

    private static int decode(Av1Encoder encoder, List<Packet> packets, int width, int height) {
        var track = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(encoder.timeBase())
                .codecConfig(encoder.codecConfig().orElse(null)).width(width).height(height).build();
        var count = new int[1];
        try (var decoder = new Av1Decoder(track)) {
            for (var p : packets) {
                decoder.decode(p, frame -> count[0]++);
            }
        }
        return count[0];
    }

    @ParameterizedTest(name = "{0}x{1}")
    @CsvSource({"1, 1", "2, 2", "7, 3", "8, 8", "16, 72", "65, 33", "130, 9", "17, 200"})
    @DisplayName("tiny and odd sizes encode and decode")
    void sizes(int width, int height) {
        var pictures = noise(width, height, 4, width * 31L + height);
        var packets = encode(Av1EncoderOptions.forRealtime().toBuilder().bitrate(64_000).dropFrames(false)
                .build(), width,
                height, pictures);
        assertEquals(4, packets.size());
    }

    @Test
    @DisplayName("flat pictures, noise and a scene cut encode and decode")
    void content() {
        var pictures = new ArrayList<YuvPicture>();
        pictures.addAll(flat(160, 96, 12));
        pictures.addAll(noise(160, 96, 6, 5));
        pictures.addAll(Vp9TestVideo.synthesize(160, 96, 12));
        var packets = encode(Av1EncoderOptions.builder().bitrate(200_000).build(), 160, 96, pictures);
        assertEquals(pictures.size(), packets.size());
    }

    @ParameterizedTest(name = "q {0} to {1}")
    @CsvSource({"0, 0", "63, 63", "0, 10", "50, 63"})
    @DisplayName("the quantizer extremes encode and decode")
    void quantizers(int min, int max) {
        var pictures = Vp9TestVideo.synthesize(96, 64, 6);
        var packets = encode(Av1EncoderOptions.builder().quantizers(min, max).bitrate(150_000).build(), 96, 64,
                pictures);
        assertEquals(6, packets.size());
    }

    @ParameterizedTest
    @EnumSource(Av1EncoderSpeed.class)
    @DisplayName("every speed with every option switched encodes and decodes")
    void options(Av1EncoderSpeed speed) {
        var pictures = Vp9TestVideo.synthesize(320, 176, 16);
        var options = Av1EncoderOptions.builder().speed(speed).errorResilient(true)
                .adaptiveQuantization(false).keyframeInterval(7).tileColumnsLog2(2)
                .threads(3).bitrate(300_000).build();
        assertEquals(16, encode(options, 320, 176, pictures).size());
        assertEquals(16, encode(options.toBuilder().adaptiveQuantization(true).errorResilient(false).build(), 320, 176,
                pictures).size());
    }

    @Test
    @DisplayName("the rate settles on the target over a long run")
    void rate() {
        var pictures = Vp9TestVideo.synthesize(320, 240, 30);
        var frames = new ArrayList<YuvPicture>();
        for (var i = 0; i < 180; i++) {
            var phase = i % 58;
            frames.add(pictures.get(phase < 30 ? phase : 58 - phase));
        }
        var options = Av1EncoderOptions.forRealtime().toBuilder().bitrate(300_000).dropFrames(false).build();
        var packets = encode(options, 320, 240, frames);
        long bytes = 0;
        for (var i = 60; i < packets.size(); i++) {
            bytes += packets.get(i).size();
        }
        var kbps = bytes * 8 * 30.0 / (packets.size() - 60) / 1000;
        assertTrue(kbps > 240 && kbps < 360, "rate " + kbps + " kbps");
    }
}
