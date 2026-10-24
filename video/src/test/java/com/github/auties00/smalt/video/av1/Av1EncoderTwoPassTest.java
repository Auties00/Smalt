package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers two-pass good-quality coding: the first pass's statistics and their serialized form, the
 * second pass's size against its bitrate over a video whose complexity changes, key frames placed
 * at a scene cut the lookahead alone cannot see, and the checks of the options and the encoder.
 */
class Av1EncoderTwoPassTest {
    private static final Rational TIME_BASE = new Rational(1, 90_000);

    private static final int WIDTH = 128;

    private static final int HEIGHT = 96;

    /** A video of three passages: a still picture, a busy noisy one and a moving one; then a cut. */
    static List<YuvPicture> video(int frames) {
        var moving = Vp9TestVideo.synthesize(WIDTH, HEIGHT, frames);
        var random = new SplittableRandom(7);
        var out = new ArrayList<YuvPicture>();
        for (var i = 0; i < frames; i++) {
            var source = moving.get(i < frames / 3 ? 0 : i);
            var picture = YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            System.arraycopy(source.y(), 0, picture.y(), 0, picture.y().length);
            System.arraycopy(source.u(), 0, picture.u(), 0, picture.u().length);
            System.arraycopy(source.v(), 0, picture.v(), 0, picture.v().length);
            if (i >= frames / 3 && i < 2 * frames / 3) {
                for (var p = 0; p < picture.y().length; p++) {
                    picture.y()[p] = (byte) Math.clamp((picture.y()[p] & 0xFF) + random.nextInt(-24, 25), 0, 255);
                }
            }
            out.add(picture);
        }
        return out;
    }

    static Av1FirstPassStatistics firstPass(List<YuvPicture> pictures, Av1EncoderOptions options) {
        var pass = new Av1FirstPass(WIDTH, HEIGHT, options);
        for (var p : pictures) {
            pass.analyze(p);
        }
        assertEquals(pictures.size(), pass.frameCount());
        return pass.statistics();
    }

    static List<Packet> encode(List<YuvPicture> pictures, Av1EncoderOptions options) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new Av1Encoder(WIDTH, HEIGHT, options)) {
            for (var i = 0; i < pictures.size(); i++) {
                encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
            }
            encoder.flush(packets::add);
        }
        return packets;
    }

    static long bits(List<Packet> packets) {
        var total = 0L;
        for (var p : packets) {
            total += p.data().length * 8L;
        }
        return total;
    }

    @ParameterizedTest(name = "{0} bit/s")
    @ValueSource(longs = {150_000, 400_000})
    @DisplayName("the second pass's size is within 2% of its bitrate over the video")
    void size(long bitrate) {
        var frames = 90;
        var pictures = video(frames);
        var options = Av1EncoderOptions.ofBitrate(bitrate).toBuilder().speed(Av1EncoderSpeed.FAST).lookahead(20)
                .timeBase(TIME_BASE).build();
        var statistics = firstPass(pictures, options);
        var packets = encode(pictures, options.toBuilder().firstPassStatistics(statistics).build());
        assertEquals(frames, packets.size());
        var target = bitrate * frames / 30.0;
        var error = Math.abs(bits(packets) - target) / target;
        assertTrue(error < 0.02, "two-pass size " + bits(packets) + " against " + target + ", error " + error);
    }

    @Test
    @DisplayName("places a key frame at a scene cut beyond the lookahead")
    void sceneCut() {
        var frames = 40;
        var first = Vp9TestVideo.synthesize(WIDTH, HEIGHT, frames);
        var pictures = new ArrayList<YuvPicture>();
        for (var i = 0; i < frames; i++) {
            var source = first.get(i);
            if (i < frames / 2) {
                pictures.add(source);
                continue;
            }
            // a different scene: the picture mirrored and inverted
            var picture = YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    picture.y()[y * WIDTH + x] = (byte) (255 - (source.y()[(HEIGHT - 1 - y) * WIDTH + x] & 0xFF));
                }
            }
            System.arraycopy(source.u(), 0, picture.u(), 0, picture.u().length);
            System.arraycopy(source.v(), 0, picture.v(), 0, picture.v().length);
            pictures.add(picture);
        }
        var options = Av1EncoderOptions.ofQuality(50).toBuilder().speed(Av1EncoderSpeed.FASTER).lookahead(2)
                .timeBase(TIME_BASE).build();
        var single = encode(pictures, options);
        var statistics = firstPass(pictures, options);
        var two = encode(pictures, options.toBuilder().firstPassStatistics(statistics).build());
        assertFalse(single.get(frames / 2).keyframe(), "one pass sees no cut with two frames of lookahead");
        assertTrue(two.get(frames / 2).keyframe(), "two passes code the cut as a key frame");
        var longer = encode(pictures, options.toBuilder().lookahead(10).build());
        assertTrue(longer.get(frames / 2).keyframe(), "one pass codes the cut as a key frame with a longer lookahead");
        for (var i = 1; i < frames; i++) {
            if (i != frames / 2) {
                assertFalse(two.get(i).keyframe(), "frame " + i + " is not a key frame");
            }
        }
    }

    @Test
    @DisplayName("statistics serialize and parse back, and malformed bytes are rejected")
    void serialization() {
        var pictures = video(12);
        var options = Av1EncoderOptions.ofBitrate(200_000).toBuilder().timeBase(TIME_BASE).build();
        var statistics = firstPass(pictures, options);
        assertEquals(12, statistics.frameCount());
        assertEquals(WIDTH, statistics.width());
        var parsed = Av1FirstPassStatistics.parse(statistics.toByteArray());
        assertEquals(statistics, parsed);
        assertEquals(statistics.hashCode(), parsed.hashCode());
        var bytes = statistics.toByteArray();
        assertThrows(IllegalArgumentException.class, () -> Av1FirstPassStatistics.parse(new byte[3]));
        var truncated = java.util.Arrays.copyOf(bytes, bytes.length - 8);
        assertThrows(IllegalArgumentException.class, () -> Av1FirstPassStatistics.parse(truncated));
        var badMagic = bytes.clone();
        badMagic[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> Av1FirstPassStatistics.parse(badMagic));
        var badVersion = bytes.clone();
        badVersion[11] = 9;
        assertThrows(IllegalArgumentException.class, () -> Av1FirstPassStatistics.parse(badVersion));
        var notFinite = bytes.clone();
        // the first value's exponent bits all set: an infinity or a NaN
        notFinite[24] = 0x7F;
        notFinite[25] = (byte) 0xF0;
        assertThrows(IllegalArgumentException.class, () -> Av1FirstPassStatistics.parse(notFinite));
        // the coded error of the first frame, negative
        var negativeError = bytes.clone();
        java.nio.ByteBuffer.wrap(negativeError).putDouble(24 + 8, -1);
        assertThrows(IllegalArgumentException.class, () -> Av1FirstPassStatistics.parse(negativeError));
        // the share of inter blocks of the first frame, above one
        var share = bytes.clone();
        java.nio.ByteBuffer.wrap(share).putDouble(24 + 3 * 8, 1.5);
        assertThrows(IllegalArgumentException.class, () -> Av1FirstPassStatistics.parse(share));
        var empty = java.util.Arrays.copyOf(bytes, 24);
        java.nio.ByteBuffer.wrap(empty).putInt(20, 0);
        assertEquals(0, Av1FirstPassStatistics.parse(empty).frameCount());
    }

    @Test
    @DisplayName("rejects statistics outside the good-quality usage and of another picture size")
    void rejects() {
        var pictures = video(4);
        var options = Av1EncoderOptions.ofBitrate(200_000).toBuilder().timeBase(TIME_BASE).build();
        var statistics = firstPass(pictures, options);
        assertThrows(IllegalArgumentException.class,
                () -> Av1EncoderOptions.forRealtime().toBuilder().firstPassStatistics(statistics).build());
        assertThrows(IllegalArgumentException.class,
                () -> Av1EncoderOptions.forIntraOnly(50).toBuilder().firstPassStatistics(statistics).build());
        var second = options.toBuilder().firstPassStatistics(statistics).build();
        assertThrows(IllegalArgumentException.class, () -> new Av1Encoder(WIDTH + 2, HEIGHT, second));
        assertThrows(IllegalArgumentException.class, () -> new Av1FirstPass(0, HEIGHT, options));
        var pass = new Av1FirstPass(WIDTH, HEIGHT, options);
        assertThrows(IllegalArgumentException.class, () -> pass.analyze(Vp9TestVideo.synthesize(64, 64, 1).getFirst()));
        try (var encoder = new Av1Encoder(WIDTH, HEIGHT, second)) {
            assertThrows(IllegalArgumentException.class, () -> encoder.reconfigure(options));
        }
    }

    @Test
    @DisplayName("codes the frames past the measured ones as a single pass would")
    void beyondStatistics() {
        var pictures = video(30);
        var options = Av1EncoderOptions.ofBitrate(250_000).toBuilder().lookahead(8).timeBase(TIME_BASE).build();
        var statistics = firstPass(pictures.subList(0, 12), options);
        var packets = encode(pictures, options.toBuilder().firstPassStatistics(statistics).build());
        assertEquals(30, packets.size());
    }
}
