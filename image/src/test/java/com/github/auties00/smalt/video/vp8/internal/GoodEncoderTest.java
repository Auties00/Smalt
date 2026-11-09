package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.vp8.Vp8Decoder;
import com.github.auties00.smalt.video.vp8.Vp8EncoderOptions;
import com.github.auties00.smalt.video.vp8.Vp8EncoderSpeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the good-quality VP8 loop directly and checks that every shown frame the module's decoder
 * decodes equals the encoder's reconstruction, across speeds, rate control modes, temporal
 * filter settings and one-pass and two-pass planning, and that the planning codes hidden
 * alternate references and scene-cut key frames.
 */
@DisplayName("good-quality VP8 loop")
class GoodEncoderTest {
    static final int WIDTH = 96;

    static final int HEIGHT = 64;

    static final long TICK = RtEncoder.TICKS_PER_SECOND / 30;

    record Coded(List<GoodEncoder.Packet> packets, List<byte[]> recons, GoodEncoder encoder) {
    }

    /** Returns a textured scene panning slowly, with a moving square, cut to another scene at {@code cut}. */
    static List<YuvPicture> clip(int frames, int cut) {
        var pictures = new ArrayList<YuvPicture>();
        for (var t = 0; t < frames; t++) {
            var picture = YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            var scene = t < cut ? 0 : 1;
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    var u = x + t * (scene == 0 ? 1 : -2);
                    var value = scene == 0
                            ? 60 + ((u * 7 + y * 13) % 53) + ((u / 8 + y / 8) % 2) * 40
                            : 200 - ((u * 3 + y * 5) % 37) - ((u / 5) % 3) * 25;
                    var dx = x - (10 + t * 2) % WIDTH;
                    var dy = y - HEIGHT / 3;
                    if (dx >= 0 && dx < 12 && dy >= 0 && dy < 12) {
                        value = 235;
                    }
                    picture.y()[y * picture.yStride() + x] = (byte) Math.clamp(value + ((x * y + t) % 5), 16, 235);
                }
            }
            for (var y = 0; y < picture.chromaHeight(); y++) {
                for (var x = 0; x < picture.chromaWidth(); x++) {
                    picture.u()[y * picture.uvStride() + x] = (byte) (110 + (x + y + t * scene) % 13);
                    picture.v()[y * picture.uvStride() + x] = (byte) (140 - (x * 3 + y + t) % 11);
                }
            }
            pictures.add(picture);
        }
        return pictures;
    }

    static GoodEncoder.Settings settings(int mode, long bitrate, int cq, int filterFrames, int speed, double[] stats,
                                         int statsFrames) {
        return settings(mode, bitrate, cq, filterFrames, speed, stats, statsFrames, 128);
    }

    // speed -1 stands for the best quality
    static GoodEncoder.Settings settings(int mode, long bitrate, int cq, int filterFrames, int speed, double[] stats,
                                         int statsFrames, int keyInterval) {
        return new GoodEncoder.Settings(mode, bitrate, 30, GoodEncoder.qindexOf(4), GoodEncoder.qindexOf(63),
                GoodEncoder.qindexOf(cq), keyInterval, 25, true, 5, filterFrames, Math.max(speed, 0), speed < 0, stats,
                statsFrames);
    }

    static Coded encode(List<YuvPicture> pictures, GoodEncoder.Settings settings, int threads) {
        var options = Vp8EncoderOptions.ofBitrate(settings.bitrate()).toBuilder()
                .speed(Vp8EncoderSpeed.of(settings.speed())).threads(threads).build();
        var good = new GoodEncoder(WIDTH, HEIGHT, options);
        good.configure(settings);
        var engine = good.engine();
        engine.keepReconstruction(true);
        var packets = new ArrayList<GoodEncoder.Packet>();
        var recons = new ArrayList<byte[]>();
        java.util.function.Consumer<GoodEncoder.Packet> sink = packet -> {
            packets.add(packet);
            if (!packet.hidden()) {
                var recon = YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
                engine.copyReconstruction(recon);
                recons.add(i420(recon));
            }
        };
        for (var i = 0; i < pictures.size(); i++) {
            good.encode(pictures.get(i), i * 3000L, 3000, TICK, false, sink);
        }
        good.flush(sink);
        return new Coded(packets, recons, good);
    }

    static double[] firstPass(List<YuvPicture> pictures) {
        var analyzer = new FirstPassAnalyzer(WIDTH, HEIGHT, com.github.auties00.smalt.MediaLimits.DEFAULT);
        var records = new double[pictures.size() * FirstPassAnalyzer.FIELDS];
        for (var i = 0; i < pictures.size(); i++) {
            analyzer.analyze(pictures.get(i), TICK, records, i * FirstPassAnalyzer.FIELDS, 1);
        }
        return records;
    }

    static byte[] i420(YuvPicture picture) {
        var out = new byte[WIDTH * HEIGHT * 3 / 2];
        for (var y = 0; y < HEIGHT; y++) {
            System.arraycopy(picture.y(), y * picture.yStride(), out, y * WIDTH, WIDTH);
        }
        var cw = WIDTH / 2;
        for (var y = 0; y < HEIGHT / 2; y++) {
            System.arraycopy(picture.u(), y * picture.uvStride(), out, WIDTH * HEIGHT + y * cw, cw);
            System.arraycopy(picture.v(), y * picture.uvStride(), out, WIDTH * HEIGHT * 5 / 4 + y * cw, cw);
        }
        return out;
    }

    static List<byte[]> decode(List<GoodEncoder.Packet> packets) {
        var frames = new ArrayList<byte[]>();
        var track = VideoTrack.builder().id(1).codec(Codec.VP8).timeBase(new Rational(1, 30)).width(WIDTH)
                .height(HEIGHT).build();
        try (var decoder = new Vp8Decoder(track)) {
            var pts = 0L;
            for (var packet : packets) {
                decoder.decode(new com.github.auties00.smalt.track.Packet(1, packet.data(), pts, pts, 1, packet.key()),
                        frame -> frames.add(i420((YuvPicture) frame.picture())));
                pts++;
            }
        }
        return frames;
    }

    static void assertDecodesToReconstruction(Coded coded, int pictures) {
        var decoded = decode(coded.packets());
        assertEquals(pictures, coded.recons().size(), "shown frames");
        assertEquals(pictures, decoded.size(), "decoded frames");
        for (var i = 0; i < pictures; i++) {
            assertArrayEquals(coded.recons().get(i), decoded.get(i), "frame " + i);
        }
    }

    @ParameterizedTest(name = "mode {0}, speed {1}, filter frames {2}, two pass {3}")
    @CsvSource({
            "0, 4, 7, false", "0, 4, 0, false", "0, 4, 7, true", "1, 4, 7, false", "2, 4, 7, false",
            "0, 3, 7, false", "0, 2, 7, true", "0, 1, 7, false", "0, 0, 7, false", "2, 0, 3, true",
            "0, -1, 7, false", "0, -1, 7, true", "2, -1, 0, false"
    })
    @DisplayName("every shown frame decodes to the encoder's reconstruction")
    void reconstruction(int mode, int speed, int filterFrames, boolean twoPass) {
        var pictures = clip(48, 30);
        double[] stats = twoPass ? firstPass(pictures) : null;
        var coded = encode(pictures, settings(mode, 200_000, 20, filterFrames, speed, stats, twoPass ? 48 : 0), 1);
        assertDecodesToReconstruction(coded, pictures.size());
        assertTrue(coded.packets().stream().anyMatch(GoodEncoder.Packet::hidden), "an alternate reference is coded");
        var keys = coded.packets().stream().filter(GoodEncoder.Packet::key).count();
        assertTrue(keys >= 2, "a key frame at the scene cut, got " + keys);
    }

    @Test
    @DisplayName("the stream does not depend on the threads")
    void threads() {
        var pictures = clip(40, 25);
        var one = encode(pictures, settings(0, 200_000, 20, 7, 4, null, 0), 1);
        var four = encode(pictures, settings(0, 200_000, 20, 7, 4, null, 0), 4);
        assertEquals(one.packets().size(), four.packets().size());
        for (var i = 0; i < one.packets().size(); i++) {
            assertArrayEquals(one.packets().get(i).data(), four.packets().get(i).data(), "packet " + i);
        }
    }

    @Test
    @DisplayName("the speed 0 stream, with its regular quantizer and coefficient optimization, does not depend on the threads")
    void threadsSpeed0() {
        var pictures = clip(20, 12);
        var one = encode(pictures, settings(0, 200_000, 20, 7, 0, null, 0), 1);
        var four = encode(pictures, settings(0, 200_000, 20, 7, 0, null, 0), 4);
        assertEquals(one.packets().size(), four.packets().size());
        for (var i = 0; i < one.packets().size(); i++) {
            assertArrayEquals(one.packets().get(i).data(), four.packets().get(i).data(), "packet " + i);
        }
    }

    @Test
    @DisplayName("the best-quality stream does not depend on the threads and differs from the speed 0 stream")
    void threadsBest() {
        var pictures = clip(20, 12);
        var one = encode(pictures, settings(0, 200_000, 20, 7, -1, null, 0), 1);
        var four = encode(pictures, settings(0, 200_000, 20, 7, -1, null, 0), 4);
        var speed0 = encode(pictures, settings(0, 200_000, 20, 7, 0, null, 0), 1);
        assertEquals(one.packets().size(), four.packets().size());
        var differs = false;
        for (var i = 0; i < one.packets().size(); i++) {
            assertArrayEquals(one.packets().get(i).data(), four.packets().get(i).data(), "packet " + i);
            differs |= i >= speed0.packets().size()
                    || !java.util.Arrays.equals(one.packets().get(i).data(), speed0.packets().get(i).data());
        }
        assertTrue(differs, "the best quality codes the stream of speed 0");
    }

    @ParameterizedTest(name = "speed {0}")
    @CsvSource({"0", "2", "4", "-1"})
    @DisplayName("key frames the interval forces are coded and decode to the reconstruction")
    void forcedKeyFrames(int speed) {
        var pictures = clip(40, 1000);
        var stats = firstPass(pictures);
        var coded = encode(pictures, settings(0, 200_000, 20, 7, speed, stats, 40, 12), 1);
        assertDecodesToReconstruction(coded, pictures.size());
        assertTrue(coded.encoder().forcedKeyFrames >= 2, "forced key frames: " + coded.encoder().forcedKeyFrames);
    }

    @Test
    @DisplayName("a bitrate below the largest quantizer's widens the zero bins and still decodes")
    void overQuantization() {
        var pictures = clip(24, 1000);
        var stats = firstPass(pictures);
        var coded = encode(pictures, settings(0, 2_000, 20, 7, 0, stats, 24), 1);
        assertDecodesToReconstruction(coded, pictures.size());
        assertTrue(coded.encoder().maxZbinOverQuant > 0, "zero-bin over-quantization");
    }

    @Test
    @DisplayName("first-pass records do not depend on the threads")
    void firstPassThreads() {
        var pictures = clip(12, 6);
        var one = firstPass(pictures);
        var analyzer = new FirstPassAnalyzer(WIDTH, HEIGHT, com.github.auties00.smalt.MediaLimits.DEFAULT);
        var four = new double[one.length];
        for (var i = 0; i < pictures.size(); i++) {
            analyzer.analyze(pictures.get(i), TICK, four, i * FirstPassAnalyzer.FIELDS, 4);
        }
        assertArrayEquals(one, four);
    }
}
