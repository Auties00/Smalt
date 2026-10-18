package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.internal.encoder.Av1EncTesting;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the public AV1 encoder: its options, the packets it produces, their decoding with the
 * module's decoder and with ffmpeg's libaom, and the real-time controls (key frame requests,
 * bitrate changes, size changes and frame dropping).
 */
class Av1EncoderTest {
    private static final Rational TIME_BASE = new Rational(1, 90_000);

    // the key frame limit of the real-time preset, in percent of the per-frame bandwidth
    private static final int KEY_LIMIT = 600;

    private static List<Packet> encodeAll(Av1Encoder encoder, List<YuvPicture> pictures) {
        var packets = new ArrayList<Packet>();
        for (var i = 0; i < pictures.size(); i++) {
            encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
        }
        return packets;
    }

    private static List<YuvPicture> decode(Av1Encoder encoder, List<Packet> packets, int width, int height) {
        var track = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(TIME_BASE)
                .codecConfig(encoder.codecConfig().orElse(null)).width(width).height(height).build();
        var out = new ArrayList<YuvPicture>();
        try (var decoder = new Av1Decoder(track)) {
            for (var p : packets) {
                decoder.decode(p, frame -> out.add(Av1EncTesting.copy((YuvPicture) frame.picture())));
            }
        }
        return out;
    }

    private static double psnr(List<YuvPicture> source, List<YuvPicture> decoded) {
        var total = 0.0;
        for (var i = 0; i < decoded.size(); i++) {
            total += Av1EncTesting.psnr(Av1EncTesting.raw(source.get(i)), Av1EncTesting.raw(decoded.get(i)));
        }
        return total / decoded.size();
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("the presets hold the documented settings")
        void presets() {
            var defaults = Av1EncoderOptions.defaults();
            assertEquals(Av1EncoderSpeed.BALANCED, defaults.speed());
            assertEquals(OptionalInt.empty(), defaults.keyframeInterval());
            assertFalse(defaults.dropFrames());
            assertFalse(defaults.errorResilient());
            assertEquals(0, defaults.minQuantizer());
            assertEquals(63, defaults.maxQuantizer());
            var realtime = Av1EncoderOptions.forRealtime();
            assertEquals(Av1EncoderSpeed.FASTER, realtime.speed());
            assertTrue(realtime.dropFrames());
            assertTrue(realtime.errorResilient());
            assertEquals(Duration.ofMillis(500), realtime.initialBufferLevel());
            assertEquals(OptionalInt.empty(), defaults.maxKeyframeBitratePercent());
            assertEquals(OptionalInt.of(KEY_LIMIT), realtime.maxKeyframeBitratePercent());
            assertEquals(OptionalInt.empty(), defaults.quality());
            var intra = Av1EncoderOptions.forIntraOnly(70);
            assertEquals(OptionalInt.of(70), intra.quality());
            assertEquals(ChromaFormat.YUV420, intra.chromaFormat());
            assertEquals(8, intra.bitDepth());
        }

        @Test
        @DisplayName("toBuilder changes settings and the builder round-trips")
        void toBuilder() {
            var base = Av1EncoderOptions.forRealtime();
            var changed = base.toBuilder().bitrate(250_000).speed(Av1EncoderSpeed.FASTEST).threads(4)
                    .tileColumnsLog2(2).keyframeInterval(60).quantizers(4, 40).build();
            assertEquals(250_000, changed.bitrate());
            assertEquals(Av1EncoderSpeed.FASTEST, changed.speed());
            assertEquals(4, changed.threads());
            assertEquals(2, changed.tileColumnsLog2());
            assertEquals(OptionalInt.of(60), changed.keyframeInterval());
            assertEquals(40, changed.maxQuantizer());
            assertEquals(base.frameRate(), changed.frameRate());
            assertEquals(changed, changed.toBuilder().build());
            assertEquals(changed.hashCode(), changed.toBuilder().build().hashCode());
            assertNotEquals(base, changed);
        }

        static Stream<Supplier<Av1EncoderOptions>> invalid() {
            var o = Av1EncoderOptions.defaults();
            return Stream.of(
                    () -> o.toBuilder().bitrate(0).build(),
                    () -> o.toBuilder().frameRate(new Rational(1, 100)).build(),
                    () -> o.toBuilder().keyframeInterval(0).build(),
                    () -> o.toBuilder().quantizers(10, 5).build(),
                    () -> o.toBuilder().quantizers(0, 64).build(),
                    () -> o.toBuilder().bufferSize(Duration.ofMillis(100)).initialBufferLevel(Duration.ofMillis(200))
                            .optimalBufferLevel(Duration.ofMillis(50)).build(),
                    () -> o.toBuilder().tileColumnsLog2(7).build(),
                    () -> o.toBuilder().threads(-1).build(),
                    () -> o.toBuilder().maxKeyframeBitratePercent(99).build(),
                    () -> o.toBuilder().quality(101).build(),
                    () -> o.toBuilder().pictureFormat(ChromaFormat.YUV444, 8).build(),
                    () -> o.toBuilder().pictureFormat(ChromaFormat.YUV420, 10).build(),
                    () -> Av1EncoderOptions.forIntraOnly(50).toBuilder().pictureFormat(ChromaFormat.YUV420, 9).build());
        }

        @ParameterizedTest
        @MethodSource("invalid")
        @DisplayName("out-of-range settings are rejected")
        void rejects(Supplier<Av1EncoderOptions> options) {
            assertThrows(IllegalArgumentException.class, options::get);
        }
    }

    @Nested
    @DisplayName("key frame sizing")
    class KeyFrames {
        private static int budget(long bitrate) {
            return (int) (bitrate / 30 / 8);
        }

        @ParameterizedTest
        @ValueSource(ints = {300_000, 600_000, 1_200_000})
        @DisplayName("the first key frame stays within one and a half times the key frame limit")
        void firstKey(int bitrate) {
            var pictures = Vp9TestVideo.synthesize(320, 240, 2);
            try (var encoder = new Av1Encoder(320, 240, Av1EncoderOptions.forRealtime().toBuilder().bitrate(bitrate)
                    .build())) {
                var key = encoder.encodeFrame(new VideoFrame(pictures.getFirst(), 0, 3000, false)).orElse(null);
                assertTrue(key.keyframe());
                assertTrue(key.size() <= budget(bitrate) * KEY_LIMIT * 3 / 200,
                        key.size() + " bytes, budget " + budget(bitrate));
            }
        }

        @Test
        @DisplayName("a size change keeps the rate control and limits the key frame after it")
        void resize() {
            var pictures = Vp9TestVideo.synthesize(320, 240, 30);
            var smaller = Vp9TestVideo.synthesize(240, 176, 10);
            var bitrate = 500_000;
            try (var encoder = new Av1Encoder(320, 240, Av1EncoderOptions.forRealtime().toBuilder().bitrate(bitrate)
                    .build())) {
                encodeAll(encoder, pictures);
                encoder.reconfigure(240, 176);
                assertEquals(bitrate * 600L / 1000, encoder.stats().bufferLevelBits());
                var packets = new ArrayList<Packet>();
                for (var i = 0; i < smaller.size(); i++) {
                    encoder.encode(new VideoFrame(smaller.get(i), (30 + i) * 3000L, 3000, false), packets::add);
                }
                assertEquals(10, packets.size());
                assertTrue(packets.getFirst().keyframe());
                assertTrue(packets.getFirst().size() <= budget(bitrate) * KEY_LIMIT * 3 / 200,
                        packets.getFirst().size() + " bytes, budget " + budget(bitrate));
                assertEquals(10, decode(encoder, packets, 240, 176).size());
            }
        }
    }

    @Nested
    @DisplayName("encoding")
    class Encoding {
        @ParameterizedTest
        @EnumSource(Av1EncoderSpeed.class)
        @DisplayName("every speed produces a stream that decodes at the target rate")
        void speeds(Av1EncoderSpeed speed) {
            var pictures = Vp9TestVideo.synthesize(176, 144, 30);
            var options = Av1EncoderOptions.builder().speed(speed).bitrate(300_000).build();
            try (var encoder = new Av1Encoder(176, 144, options)) {
                var packets = encodeAll(encoder, pictures);
                assertEquals(pictures.size(), packets.size());
                assertTrue(packets.getFirst().keyframe());
                assertTrue(packets.stream().skip(1).noneMatch(Packet::keyframe));
                var decoded = decode(encoder, packets, 176, 144);
                assertEquals(pictures.size(), decoded.size());
                assertTrue(psnr(pictures, decoded) > 28, "psnr " + psnr(pictures, decoded));
                var stats = encoder.stats();
                assertEquals(30, stats.framesEncoded());
                assertEquals(1, stats.keyframesEncoded());
                assertEquals(packets.stream().mapToLong(Packet::size).sum(), stats.bytesEncoded());
            }
        }

        @Test
        @DisplayName("the codec configuration is an av1C record of the main profile")
        void codecConfig() {
            try (var encoder = new Av1Encoder(320, 240, Av1EncoderOptions.forRealtime())) {
                var av1c = encoder.codecConfig().orElseThrow();
                assertEquals(0x81, av1c[0] & 0xFF);
                assertEquals(0, (av1c[1] & 0xFF) >> 5);
                assertEquals(0x0C, av1c[2] & 0x0C);
                assertEquals(1, (av1c[4] >> 3) & 0xF);
            }
        }

        @ParameterizedTest(name = "{0} {1}x{2}")
        @CsvSource({"REALTIME, 64, 2000, 4", "GOOD_QUALITY, 64, 2000, 4", "ALL_INTRA, 64, 2000, 4",
                "REALTIME, 1280, 720, 5", "REALTIME, 2000, 64, 0"})
        @DisplayName("every usage signals the smallest level whose width, height and area limits hold the picture")
        void level(Av1EncoderUsage usage, int width, int height, int level) {
            var options = switch (usage) {
                case REALTIME -> Av1EncoderOptions.forRealtime();
                case GOOD_QUALITY -> Av1EncoderOptions.ofQuality(50);
                case ALL_INTRA -> Av1EncoderOptions.forIntraOnly(50);
            };
            try (var encoder = new Av1Encoder(width, height, options)) {
                assertEquals(level, encoder.codecConfig().orElseThrow()[1] & 0x1F);
            }
        }

        @Test
        @DisplayName("odd sizes and pictures that are not 4:2:0 are encoded")
        void oddAndConverted() {
            var source = Vp9TestVideo.synthesize(98, 70, 4);
            var pictures = new ArrayList<YuvPicture>();
            for (var p : source) {
                var c = YuvPicture.allocate(97, 69, ChromaFormat.YUV444, false, ColorInfo.BT601_LIMITED);
                for (var y = 0; y < 69; y++) {
                    for (var x = 0; x < 97; x++) {
                        c.y()[y * c.yStride() + x] = p.y()[y * p.yStride() + x];
                        c.u()[y * c.uvStride() + x] = p.u()[(y / 2) * p.uvStride() + x / 2];
                        c.v()[y * c.uvStride() + x] = p.v()[(y / 2) * p.uvStride() + x / 2];
                    }
                }
                pictures.add(c);
            }
            try (var encoder = new Av1Encoder(97, 69, Av1EncoderOptions.forRealtime().toBuilder().dropFrames(false)
                    .build())) {
                var packets = encodeAll(encoder, pictures);
                assertEquals(4, decode(encoder, packets, 97, 69).size());
            }
        }

        @Test
        @DisplayName("a requested key frame is coded on the next frame")
        void requestKeyframe() {
            var pictures = Vp9TestVideo.synthesize(128, 96, 8);
            try (var encoder = new Av1Encoder(128, 96, Av1EncoderOptions.defaults())) {
                var packets = new ArrayList<Packet>();
                for (var i = 0; i < pictures.size(); i++) {
                    if (i == 5) {
                        encoder.requestKeyframe();
                    }
                    if (i == 3) {
                        encoder.requestKeyframe();
                    }
                    packets.add(encoder.encodeFrame(new VideoFrame(pictures.get(i), i, 1, false)).orElse(null));
                }
                var keys = packets.stream().map(Packet::keyframe).toList();
                assertEquals(List.of(true, false, false, true, false, true, false, false), keys);
                assertEquals(8, decode(encoder, packets, 128, 96).size());
                assertTrue(encoder.stats().keyframesEncoded() == 3);
            }
        }

        @ParameterizedTest(name = "{0} layers")
        @org.junit.jupiter.params.provider.ValueSource(ints = {2, 3})
        @DisplayName("temporal layers follow their pattern and every layer subset decodes")
        void temporalLayers(int layers) {
            var pictures = Vp9TestVideo.synthesize(192, 128, 16);
            try (var encoder = new Av1Encoder(192, 128, Av1EncoderOptions.forRealtime().toBuilder().dropFrames(false)
                    .temporalLayers(layers).bitrate(400_000).build())) {
                var packets = new ArrayList<Packet>();
                var ids = new ArrayList<Integer>();
                for (var i = 0; i < pictures.size(); i++) {
                    if (i == 8) {
                        encoder.requestKeyframe();
                    }
                    packets.add(encoder.encodeFrame(new VideoFrame(pictures.get(i), i, 1, false)).orElse(null));
                    ids.add(encoder.stats().lastTemporalLayer());
                }
                var expected = new ArrayList<Integer>();
                for (var i = 0; i < pictures.size(); i++) {
                    var phase = i % 8;
                    expected.add(layers == 2 ? phase & 1 : phase % 4 == 0 ? 0 : phase % 4 == 2 ? 1 : 2);
                }
                assertEquals(expected, ids);
                for (var keep = 0; keep < layers; keep++) {
                    var subset = new ArrayList<Packet>();
                    for (var i = 0; i < packets.size(); i++) {
                        if (ids.get(i) <= keep) {
                            subset.add(packets.get(i));
                        }
                    }
                    var decoded = decode(encoder, subset, 192, 128);
                    assertEquals(subset.size(), decoded.size(), "layers up to " + keep);
                }
            }
        }

        @Test
        @DisplayName("a periodic key frame interval places key frames")
        void keyframeInterval() {
            var pictures = Vp9TestVideo.synthesize(64, 64, 10);
            try (var encoder = new Av1Encoder(64, 64,
                    Av1EncoderOptions.builder().keyframeInterval(4).build())) {
                var keys = encodeAll(encoder, pictures).stream().map(Packet::keyframe).toList();
                assertEquals(List.of(true, false, false, false, true, false, false, false, true, false), keys);
            }
        }

        @Test
        @DisplayName("a size change codes a key frame at the new size")
        void resize() {
            var small = Vp9TestVideo.synthesize(96, 64, 4);
            var large = Vp9TestVideo.synthesize(160, 112, 4);
            try (var encoder = new Av1Encoder(96, 64, Av1EncoderOptions.forRealtime())) {
                var first = encodeAll(encoder, small);
                assertEquals(4, decode(encoder, first, 96, 64).size());
                encoder.reconfigure(160, 112);
                assertEquals(160, encoder.width());
                var second = new ArrayList<Packet>();
                for (var i = 0; i < large.size(); i++) {
                    second.add(encoder.encodeFrame(new VideoFrame(large.get(i), 100_000 + i, 1, false)).orElse(null));
                }
                assertTrue(second.getFirst().keyframe());
                var decoded = decode(encoder, second, 160, 112);
                assertEquals(4, decoded.size());
                assertEquals(160, decoded.getFirst().width());
                assertThrows(IllegalArgumentException.class,
                        () -> encoder.encodeFrame(new VideoFrame(small.getFirst(), 1000, 1, false)));
            }
        }

        @Test
        @DisplayName("the rate follows a bitrate change")
        void bitrateChange() {
            var pictures = Vp9TestVideo.synthesize(320, 240, 60);
            try (var encoder = new Av1Encoder(320, 240, Av1EncoderOptions.forRealtime().toBuilder().bitrate(150_000)
                    .dropFrames(false).build())) {
                var low = encodeAll(encoder, pictures.subList(0, 30)).stream().skip(10).mapToLong(Packet::size).sum();
                encoder.setTargetBitrate(900_000);
                assertEquals(900_000, encoder.options().bitrate());
                var high = new ArrayList<Packet>();
                for (var i = 30; i < 60; i++) {
                    encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), high::add);
                }
                var highBytes = high.stream().skip(10).mapToLong(Packet::size).sum();
                assertTrue(highBytes > 2 * low, "low " + low + " high " + highBytes);
            }
        }

        @Test
        @DisplayName("frames are dropped when the buffer cannot hold them and dropping is on")
        void drops() {
            var pictures = Vp9TestVideo.synthesize(320, 240, 40);
            var options = Av1EncoderOptions.forRealtime().toBuilder().bitrate(20_000).quantizers(40, 52).build();
            try (var encoder = new Av1Encoder(320, 240, options)) {
                var nulls = 0;
                var packets = new ArrayList<Packet>();
                for (var i = 0; i < pictures.size(); i++) {
                    var p = encoder.encodeFrame(new VideoFrame(pictures.get(i), i, 1, false)).orElse(null);
                    if (p == null) {
                        nulls++;
                    } else {
                        packets.add(p);
                    }
                }
                assertTrue(nulls > 0);
                assertEquals(nulls, encoder.stats().framesDropped());
                assertEquals(packets.size(), decode(encoder, packets, 320, 240).size());
            }
        }

        @Test
        @DisplayName("timestamps must increase and a closed encoder refuses frames")
        void contract() {
            var pictures = Vp9TestVideo.synthesize(64, 64, 2);
            var encoder = new Av1Encoder(64, 64, Av1EncoderOptions.defaults());
            var packet = encoder.encodeFrame(new VideoFrame(pictures.get(0), 10, 1, false)).orElse(null);
            assertNotNull(packet);
            assertEquals(10, packet.pts());
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encodeFrame(new VideoFrame(pictures.get(1), 10, 1, false)));
            assertThrows(IllegalArgumentException.class, () -> encoder.reconfigure(
                    Av1EncoderOptions.builder().timeBase(Rational.MILLISECONDS).build()));
            encoder.close();
            assertThrows(IllegalStateException.class,
                    () -> encoder.encodeFrame(new VideoFrame(pictures.get(1), 20, 1, false)));
            assertThrows(MediaException.LimitExceeded.class, () -> new Av1Encoder(20_000, 16,
                    Av1EncoderOptions.defaults()));
        }

        @Test
        @DisplayName("tiled multithreaded encoding gives the same stream as one thread")
        void threadsDeterministic() {
            var pictures = Vp9TestVideo.synthesize(512, 128, 6);
            var options = Av1EncoderOptions.builder().tileColumnsLog2(2).bitrate(500_000).build();
            List<Packet> one;
            List<Packet> four;
            try (var encoder = new Av1Encoder(512, 128, options)) {
                one = encodeAll(encoder, pictures);
            }
            try (var encoder = new Av1Encoder(512, 128, options.toBuilder().threads(4).build())) {
                four = encodeAll(encoder, pictures);
                assertEquals(6, decode(encoder, four, 512, 128).size());
            }
            for (var i = 0; i < one.size(); i++) {
                assertArrayEquals(one.get(i).data(), four.get(i).data(), "packet " + i);
            }
        }

        @ParameterizedTest
        @CsvSource({"0, 4", "1, 3", "0, 2"})
        @DisplayName("superblock rows in a wavefront give the same stream as one thread")
        void rowThreadsDeterministic(int tileColumnsLog2, int threads) {
            var pictures = Vp9TestVideo.synthesize(320, 256, 8);
            var options = Av1EncoderOptions.forRealtime().toBuilder().bitrate(400_000).dropFrames(false)
                    .tileColumnsLog2(tileColumnsLog2).build();
            List<Packet> one;
            List<Packet> many;
            try (var encoder = new Av1Encoder(320, 256, options)) {
                one = encodeAll(encoder, pictures);
            }
            try (var encoder = new Av1Encoder(320, 256, options.toBuilder().threads(threads).build())) {
                many = encodeAll(encoder, pictures);
                assertEquals(8, decode(encoder, many, 320, 256).size());
            }
            assertEquals(one.size(), many.size());
            for (var i = 0; i < one.size(); i++) {
                assertArrayEquals(one.get(i).data(), many.get(i).data(), "packet " + i);
            }
        }

        @ParameterizedTest(name = "{0} {1}-bit")
        @DisplayName("intra-only streams are all key frames that decode with the module and ffmpeg alike")
        @CsvSource({"YUV420, 8", "YUV444, 10", "YUV422, 12", "MONOCHROME, 8"})
        void intraOnly(ChromaFormat format, int depth) throws Exception {
            var pictures = Vp9TestVideo.synthesize(160, 96, 4);
            var options = Av1EncoderOptions.forIntraOnly(60).toBuilder().pictureFormat(format, depth)
                    .speed(Av1EncoderSpeed.FASTER).build();
            try (var encoder = new Av1Encoder(160, 96, options)) {
                var packets = encodeAll(encoder, pictures);
                assertEquals(4, packets.size());
                assertTrue(packets.stream().allMatch(Packet::keyframe));
                assertEquals(4, encoder.stats().keyframesEncoded());
                var track = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(TIME_BASE)
                        .codecConfig(encoder.codecConfig().orElseThrow()).width(160).height(96).build();
                var decoded = new ArrayList<YuvPicture>();
                try (var decoder = new Av1Decoder(track, Av1DecoderOptions.builder().highBitDepth(true).build())) {
                    for (var p : packets) {
                        decoder.decode(p, frame -> {
                            if (frame.picture() instanceof com.github.auties00.smalt.image.Yuv16Picture deep) {
                                assertEquals(depth, deep.bitDepth());
                                assertEquals(format, deep.chromaFormat());
                            } else {
                                var yuv = (YuvPicture) frame.picture();
                                assertEquals(8, depth);
                                assertEquals(format, yuv.chromaFormat());
                                if (format == ChromaFormat.YUV420) {
                                    decoded.add(Av1EncTesting.copy(yuv));
                                }
                            }
                        });
                    }
                }
                if (format == ChromaFormat.YUV420 && depth == 8) {
                    assertEquals(4, decoded.size());
                    var yuv = decoded;
                    var psnr = psnr(pictures, yuv);
                    assertTrue(psnr > 35, "psnr " + psnr);
                    if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                        var units = packets.stream().map(Packet::data).toList();
                        var raw = Av1EncTesting.ffmpegDecode(Av1EncTesting.writeTemp(Av1EncTesting.ivf(160, 96, units)));
                        var frameSize = Av1EncTesting.raw(yuv.getFirst()).length;
                        assertEquals(frameSize * 4, raw.length);
                        for (var i = 0; i < 4; i++) {
                            assertArrayEquals(Av1EncTesting.raw(yuv.get(i)),
                                    Arrays.copyOfRange(raw, i * frameSize, (i + 1) * frameSize), "frame " + i);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("error resilient streams decode with ffmpeg's libaom like with the module's decoder")
        void interop() throws Exception {
            var pictures = Vp9TestVideo.synthesize(208, 120, 12);
            try (var encoder = new Av1Encoder(208, 120, Av1EncoderOptions.forRealtime().toBuilder().dropFrames(false)
                    .bitrate(250_000).tileColumnsLog2(1).build())) {
                var packets = encodeAll(encoder, pictures);
                var decoded = decode(encoder, packets, 208, 120);
                if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
                    return;
                }
                var units = packets.stream().map(Packet::data).toList();
                var raw = Av1EncTesting.ffmpegDecode(Av1EncTesting.writeTemp(Av1EncTesting.ivf(208, 120, units)));
                var frameSize = Av1EncTesting.raw(decoded.getFirst()).length;
                assertEquals(frameSize * decoded.size(), raw.length);
                for (var i = 0; i < decoded.size(); i++) {
                    assertArrayEquals(Av1EncTesting.raw(decoded.get(i)),
                            Arrays.copyOfRange(raw, i * frameSize, (i + 1) * frameSize), "frame " + i);
                }
            }
        }
    }
}
