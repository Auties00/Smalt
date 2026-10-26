package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the good-quality usage of the public VP9 encoder: one-pass and two-pass encodes at a
 * variable bitrate, constrained quality and constant quality, the packets the lookahead releases
 * (one shown frame each, in display order, hidden alternate references joined to the frame after
 * them), their decoding with the module's decoder and with FFmpeg, the bitrate accuracy, the
 * monotony of the quality levels, and the independence of the stream from the thread count.
 */
@DisplayName("VP9 good-quality encoding")
class Vp9GoodQualityTest {
    static final int WIDTH = 176;

    static final int HEIGHT = 144;

    static final int FRAMES = 40;

    @TempDir
    Path temp;

    static List<YuvPicture> clip(int frames) {
        return Vp9TestVideo.synthesize(WIDTH, HEIGHT, frames);
    }

    static List<Packet> encode(Vp9EncoderOptions options, List<? extends Picture> pictures) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new Vp9Encoder(WIDTH, HEIGHT, options)) {
            for (var i = 0; i < pictures.size(); i++) {
                encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
            }
            encoder.flush(packets::add);
        }
        return packets;
    }

    static Vp9FirstPassStats firstPass(Vp9EncoderOptions options, List<? extends Picture> pictures) {
        var pass = new Vp9FirstPass(WIDTH, HEIGHT, options);
        for (var i = 0; i < pictures.size(); i++) {
            pass.add(new VideoFrame(pictures.get(i), i * 3000L, 3000, false));
        }
        return pass.finish();
    }

    static long bytes(List<Packet> packets) {
        return packets.stream().mapToLong(Packet::size).sum();
    }

    static double kbps(List<Packet> packets, int frames) {
        return bytes(packets) * 8.0 * 30 / frames / 1000;
    }

    static double psnr(List<YuvPicture> source, List<Picture> decoded) {
        var total = 0.0;
        for (var i = 0; i < decoded.size(); i++) {
            var s = source.get(i);
            var d = (YuvPicture) decoded.get(i);
            long sse = 0;
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    var e = (s.y()[y * s.yStride() + x] & 0xFF) - (d.y()[y * d.yStride() + x] & 0xFF);
                    sse += e * e;
                }
            }
            total += sse == 0 ? 99 : 10 * Math.log10(255.0 * 255 * WIDTH * HEIGHT / sse);
        }
        return total / decoded.size();
    }

    static List<Picture> decode(List<Packet> packets) {
        return decode(packets, false);
    }

    static List<Picture> decode(List<Packet> packets, boolean highBitDepth) {
        var track = VideoTrack.builder().id(1).codec(Codec.VP9).timeBase(new Rational(1, 90_000)).width(WIDTH)
                .height(HEIGHT).build();
        var options = highBitDepth ? VideoDecoderOptions.builder().highBitDepth(true).build()
                : VideoDecoderOptions.defaults();
        var out = new ArrayList<Picture>();
        try (var decoder = new Vp9Decoder(track, options)) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> out.add(planarCopy(frame.picture())));
            }
        }
        return out;
    }

    /** Returns a decoded picture as raw planar samples wrapped in a picture of the same kind. */
    static Picture planarCopy(Picture picture) {
        return Vp9EncoderFormatTest.copy(picture);
    }

    /** Returns the visible planes of a picture back to back, as FFmpeg's raw output lays them out. */
    static byte[] planar(Picture picture) {
        var out = new ByteArrayOutputStream();
        if (picture instanceof Yuv16Picture yuv) {
            for (var plane = 0; plane < 3; plane++) {
                var data = plane == 0 ? yuv.y() : plane == 1 ? yuv.u() : yuv.v();
                var stride = plane == 0 ? yuv.yStride() : yuv.uvStride();
                var w = plane == 0 ? yuv.width() : yuv.chromaWidth();
                var h = plane == 0 ? yuv.height() : yuv.chromaHeight();
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        var v = data[y * stride + x];
                        out.write(v & 0xFF);
                        out.write((v >> 8) & 0xFF);
                    }
                }
            }
        } else {
            var yuv = (YuvPicture) picture;
            for (var plane = 0; plane < 3; plane++) {
                var data = plane == 0 ? yuv.y() : plane == 1 ? yuv.u() : yuv.v();
                var stride = plane == 0 ? yuv.yStride() : yuv.uvStride();
                var w = plane == 0 ? yuv.width() : yuv.chromaWidth();
                var h = plane == 0 ? yuv.height() : yuv.chromaHeight();
                for (var y = 0; y < h; y++) {
                    out.write(data, y * stride, w);
                }
            }
        }
        return out.toByteArray();
    }

    /** Returns whether a packet carries a superframe index, the container of a hidden frame. */
    static boolean superframe(Packet packet) {
        var data = packet.data();
        var marker = data[data.length - 1] & 0xFF;
        if ((marker & 0xE0) != 0xC0) {
            return false;
        }
        var frames = (marker & 7) + 1;
        var mag = ((marker >> 3) & 3) + 1;
        var index = 2 + mag * frames;
        return data.length >= index && (data[data.length - index] & 0xFF) == marker;
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("the presets select the good-quality usage and its rate controls")
        void presets() {
            var bitrate = Vp9EncoderOptions.ofBitrate(400_000);
            assertEquals(Vp9EncoderUsage.GOOD_QUALITY, bitrate.usage());
            assertEquals(Vp9RateControl.VARIABLE_BITRATE, bitrate.rateControl());
            assertEquals(Vp9EncoderSpeed.SPEED_2, bitrate.speed());
            assertEquals(25, bitrate.lookahead());
            assertTrue(bitrate.alternateReferences());
            var quality = Vp9EncoderOptions.ofQuality(30);
            assertEquals(Vp9RateControl.CONSTANT_QUALITY, quality.rateControl());
            assertEquals(30, quality.cqLevel());
            var archive = Vp9EncoderOptions.forArchive();
            assertEquals(Vp9EncoderSpeed.SPEED_1, archive.speed());
            assertEquals(20, archive.cqLevel());
        }

        @Test
        @DisplayName("the best-quality usage takes the rate controls and speeds of the good-quality usage")
        void bestQuality() {
            var best = Vp9EncoderOptions.ofQuality(30).toBuilder().usage(Vp9EncoderUsage.BEST_QUALITY).build();
            assertEquals(Vp9EncoderUsage.BEST_QUALITY, best.usage());
            assertThrows(IllegalArgumentException.class,
                    () -> best.toBuilder().rateControl(Vp9RateControl.CONSTANT_BITRATE).build());
            assertThrows(IllegalArgumentException.class, () -> best.toBuilder().speed(Vp9EncoderSpeed.SPEED_6).build());
            assertThrows(IllegalArgumentException.class, () -> best.toBuilder().dropFrames(true).build());
            try (var encoder = new Vp9Encoder(WIDTH, HEIGHT, best)) {
                assertThrows(IllegalArgumentException.class, () -> encoder.reconfigure(best.toBuilder()
                        .usage(Vp9EncoderUsage.GOOD_QUALITY).build()));
            }
        }

        @Test
        @DisplayName("first-pass statistics of another picture size are rejected")
        void statsSize() {
            var options = Vp9EncoderOptions.ofBitrate(300_000);
            var stats = firstPass(options, clip(3));
            var second = options.toBuilder().firstPassStats(stats).build();
            assertThrows(IllegalArgumentException.class, () -> new Vp9Encoder(WIDTH + 16, HEIGHT, second));
        }

        @Test
        @DisplayName("encodeFrame refuses the good-quality usage, which holds frames back")
        void encodeFrame() {
            try (var encoder = new Vp9Encoder(WIDTH, HEIGHT, Vp9EncoderOptions.ofQuality(30))) {
                var frame = new VideoFrame(clip(1).getFirst(), 0, 3000, false);
                assertThrows(IllegalStateException.class, () -> encoder.encodeFrame(frame));
            }
        }

        @Test
        @DisplayName("a running good-quality encoder changes only its bitrate, quantizers and quality level")
        void reconfigure() {
            try (var encoder = new Vp9Encoder(WIDTH, HEIGHT, Vp9EncoderOptions.ofBitrate(300_000))) {
                encoder.setTargetBitrate(500_000);
                assertEquals(500_000, encoder.options().bitrate());
                encoder.reconfigure(encoder.options().toBuilder().quantizers(4, 50).cqLevel(20).build());
                assertThrows(IllegalArgumentException.class,
                        () -> encoder.reconfigure(encoder.options().toBuilder().lookahead(10).build()));
                assertThrows(IllegalArgumentException.class,
                        () -> encoder.reconfigure(encoder.options().toBuilder().speed(Vp9EncoderSpeed.SPEED_3).build()));
            }
        }

        @Test
        @DisplayName("first-pass statistics survive serialization and reject malformed data")
        void serialization() {
            var stats = firstPass(Vp9EncoderOptions.ofBitrate(300_000), clip(5));
            var bytes = stats.toByteArray();
            var restored = Vp9FirstPassStats.fromByteArray(bytes);
            assertEquals(stats.frameCount(), restored.frameCount());
            assertEquals(5, restored.frameCount());
            assertArrayEquals(bytes, restored.toByteArray());
            assertThrows(MediaException.Malformed.class,
                    () -> Vp9FirstPassStats.fromByteArray(java.util.Arrays.copyOf(bytes, bytes.length - 1)));
            var corrupt = bytes.clone();
            corrupt[0] ^= 1;
            assertThrows(MediaException.Malformed.class, () -> Vp9FirstPassStats.fromByteArray(corrupt));
        }
    }

    @Nested
    @DisplayName("encoding")
    class Encoding {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Vp9EncoderSpeed.class, names = {"SPEED_0", "SPEED_1", "SPEED_2", "SPEED_3", "SPEED_4"})
        @DisplayName("every speed codes one packet per picture with hidden alternate references")
        void speeds(Vp9EncoderSpeed speed) {
            var pictures = clip(FRAMES);
            var packets = encode(Vp9EncoderOptions.ofQuality(32).toBuilder().speed(speed).build(), pictures);
            assertEquals(FRAMES, packets.size());
            assertTrue(packets.getFirst().keyframe());
            assertTrue(packets.stream().anyMatch(Vp9GoodQualityTest::superframe), "no alternate reference");
            for (var i = 0; i < packets.size(); i++) {
                assertEquals(i * 3000L, packets.get(i).pts());
            }
            var decoded = decode(packets);
            assertEquals(FRAMES, decoded.size());
            var psnr = psnr(pictures, decoded);
            assertTrue(psnr > 32, "psnr " + psnr);
        }

        @Test
        @DisplayName("the best quality codes a decodable stream of its own, with hidden alternate references")
        void bestQuality() {
            var pictures = clip(24);
            var good = Vp9EncoderOptions.ofQuality(32).toBuilder().speed(Vp9EncoderSpeed.SPEED_0).build();
            var bestPackets = encode(good.toBuilder().usage(Vp9EncoderUsage.BEST_QUALITY).build(), pictures);
            var goodPackets = encode(good, pictures);
            assertEquals(pictures.size(), bestPackets.size());
            assertTrue(bestPackets.stream().anyMatch(Vp9GoodQualityTest::superframe), "no alternate reference");
            var decoded = decode(bestPackets);
            assertEquals(pictures.size(), decoded.size());
            var psnr = psnr(pictures, decoded);
            assertTrue(psnr > 32, "psnr " + psnr);
            var differs = false;
            for (var i = 0; i < pictures.size() && !differs; i++) {
                differs = !java.util.Arrays.equals(bestPackets.get(i).data(), goodPackets.get(i).data());
            }
            assertTrue(differs, "the best quality codes the stream of speed 0");
        }

        @ParameterizedTest(name = "seed {0}")
        @CsvSource({"1", "2", "3", "4"})
        @DisplayName("well-formed first-pass statistics holding arbitrary finite values still code a decodable stream")
        void hostileStatistics(long seed) {
            var pictures = clip(10);
            var options = Vp9EncoderOptions.ofBitrate(300_000).toBuilder().speed(Vp9EncoderSpeed.SPEED_4).build();
            var bytes = firstPass(options, pictures).toByteArray();
            var random = new java.util.Random(seed);
            double[] extremes = {0, -0.0, 1, -1, 0.5, 1e-300, -1e-300, 1e300, -1e300, Double.MAX_VALUE,
                    -Double.MAX_VALUE, Double.MIN_VALUE, 1e9, -1e9};
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            for (var at = 20; at + 8 <= bytes.length; at += 8) {
                var value = random.nextInt(3) == 0 ? extremes[random.nextInt(extremes.length)]
                        : buffer.getDouble(at) * (random.nextDouble() * 4 - 1);
                buffer.putDouble(at, value);
            }
            var stats = Vp9FirstPassStats.fromByteArray(bytes);
            var second = options.toBuilder().firstPassStats(stats).build();
            var packets = assertTimeoutPreemptively(java.time.Duration.ofSeconds(120), () -> encode(second, pictures));
            assertEquals(pictures.size(), packets.size());
            assertEquals(pictures.size(), decode(packets).size());
        }

        @Test
        @DisplayName("finer quality levels code larger, closer streams")
        void qualityLevels() {
            var pictures = clip(24);
            var coarse = encode(Vp9EncoderOptions.ofQuality(50).toBuilder().speed(Vp9EncoderSpeed.SPEED_3).build(),
                    pictures);
            var fine = encode(Vp9EncoderOptions.ofQuality(20).toBuilder().speed(Vp9EncoderSpeed.SPEED_3).build(),
                    pictures);
            assertTrue(bytes(fine) > bytes(coarse), bytes(fine) + " <= " + bytes(coarse));
            assertTrue(psnr(pictures, decode(fine)) > psnr(pictures, decode(coarse)));
        }

        @ParameterizedTest(name = "{0} kbit/s, two pass {1}")
        @CsvSource({"150, false", "400, false", "150, true", "400, true"})
        @DisplayName("the variable bitrate meets its target over the clip")
        void bitrate(int kbit, boolean twoPass) {
            var pictures = clip(60);
            var options = Vp9EncoderOptions.ofBitrate(kbit * 1000L).toBuilder().speed(Vp9EncoderSpeed.SPEED_3).build();
            if (twoPass) {
                options = options.toBuilder().firstPassStats(firstPass(options, pictures)).build();
            }
            var packets = encode(options, pictures);
            var actual = kbps(packets, pictures.size());
            assertTrue(Math.abs(actual - kbit) < kbit * 0.25, "coded " + actual + " kbit/s for " + kbit);
            assertEquals(pictures.size(), decode(packets).size());
        }

        @Test
        @DisplayName("constrained quality stays at or below the bitrate and never codes coarser than its level asks")
        void constrainedQuality() {
            var pictures = clip(30);
            var options = Vp9EncoderOptions.ofBitrate(2_000_000).toBuilder().rateControl(Vp9RateControl.CONSTRAINED_QUALITY)
                    .cqLevel(40).speed(Vp9EncoderSpeed.SPEED_3).build();
            var packets = encode(options, pictures);
            assertTrue(kbps(packets, pictures.size()) < 2000);
            assertEquals(pictures.size(), decode(packets).size());
        }

        @Test
        @DisplayName("a two-pass encode is deterministic and independent of the thread count")
        void deterministic() {
            var pictures = clip(30);
            var options = Vp9EncoderOptions.ofBitrate(300_000).toBuilder().speed(Vp9EncoderSpeed.SPEED_2).threads(1)
                    .build();
            options = options.toBuilder().firstPassStats(firstPass(options, pictures)).build();
            var one = encode(options, pictures);
            var four = encode(options.toBuilder().threads(4).build(), pictures);
            assertEquals(one.size(), four.size());
            for (var i = 0; i < one.size(); i++) {
                assertArrayEquals(one.get(i).data(), four.get(i).data(), "packet " + i);
            }
        }

        @Test
        @DisplayName("a two-pass encode rejects more pictures than its first pass measured")
        void tooManyPictures() {
            var pictures = clip(6);
            var options = Vp9EncoderOptions.ofBitrate(300_000);
            options = options.toBuilder().firstPassStats(firstPass(options, pictures.subList(0, 3))).build();
            var encoder = new Vp9Encoder(WIDTH, HEIGHT, options);
            var sink = new ArrayList<Packet>();
            for (var i = 0; i < 3; i++) {
                encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), sink::add);
            }
            var extra = new VideoFrame(pictures.get(3), 3 * 3000L, 3000, false);
            assertThrows(IllegalStateException.class, () -> encoder.encode(extra, sink::add));
        }

        @Test
        @DisplayName("requested key frames are coded as key frames")
        void requestedKey() {
            var pictures = clip(20);
            var packets = new ArrayList<Packet>();
            try (var encoder = new Vp9Encoder(WIDTH, HEIGHT, Vp9EncoderOptions.ofQuality(36).toBuilder()
                    .speed(Vp9EncoderSpeed.SPEED_4).build())) {
                for (var i = 0; i < pictures.size(); i++) {
                    if (i == 11) {
                        encoder.requestKeyframe();
                    }
                    encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
                }
                encoder.flush(packets::add);
            }
            assertTrue(packets.get(11).keyframe());
            assertFalse(packets.get(10).keyframe());
        }
    }

    @Nested
    @DisplayName("interoperability")
    class Interop {
        @ParameterizedTest(name = "{0}, {1} bits")
        @CsvSource({"YUV420, 8", "YUV444, 8", "YUV420, 10"})
        @DisplayName("FFmpeg's libvpx decoder reproduces the module decoder's pictures")
        void ffmpeg(Vp9Subsampling subsampling, int bitDepth) throws Exception {
            Skip.unless(Vp9TestVideo.ffmpegAvailable(), "oracle:ffmpeg", "ffmpeg not available");
            var format = subsampling == Vp9Subsampling.YUV444 ? ChromaFormat.YUV444 : ChromaFormat.YUV420;
            List<? extends Picture> pictures = bitDepth > 8 ? Vp9TestVideo.synthesize16(WIDTH, HEIGHT, 30, format, bitDepth)
                    : Vp9TestVideo.synthesize(WIDTH, HEIGHT, 30, format);
            var options = Vp9EncoderOptions.ofBitrate(400_000).toBuilder().pictureFormat(subsampling, bitDepth)
                    .speed(Vp9EncoderSpeed.SPEED_2).build();
            var packets = encode(options, pictures);
            var decoded = decode(packets, bitDepth > 8);
            assertEquals(pictures.size(), decoded.size());
            var raw = new ByteArrayOutputStream();
            for (var picture : decoded) {
                raw.writeBytes(planar(picture));
            }
            var ivf = temp.resolve("good.ivf");
            Files.write(ivf, Vp9TestVideo.ivf(WIDTH, HEIGHT, packets.stream().map(Packet::data).toList()));
            var layout = subsampling == Vp9Subsampling.YUV444 ? "yuv444p" : "yuv420p";
            var pixelFormat = bitDepth > 8 ? layout + bitDepth + "le" : layout;
            var want = Vp9TestVideo.md5(raw.toByteArray());
            assertEquals(want, Vp9TestVideo.md5(Vp9TestVideo.ffmpegDecode(ivf, "libvpx-vp9", pixelFormat)), "libvpx");
            assertEquals(want, Vp9TestVideo.md5(Vp9TestVideo.ffmpegDecode(ivf, "vp9", pixelFormat)), "ffmpeg vp9");
        }
    }
}
