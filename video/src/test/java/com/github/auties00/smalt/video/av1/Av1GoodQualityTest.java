package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the good-quality usage of the public AV1 encoder: its options and presets, the packets
 * the lookahead releases (one shown frame each, in display order), their decoding with the
 * module's decoder and with ffmpeg's libaom, key frame placement, constant quality and variable
 * bitrate rate control, and the independence of the stream from the thread count.
 */
class Av1GoodQualityTest {
    private static final Rational TIME_BASE = new Rational(1, 90_000);

    private static List<Packet> encodeAll(Av1Encoder encoder, List<YuvPicture> pictures, int first) {
        var packets = new ArrayList<Packet>();
        for (var i = 0; i < pictures.size(); i++) {
            encoder.encode(new VideoFrame(pictures.get(i), (first + i) * 3000L, 3000, false), packets::add);
        }
        encoder.flush(packets::add);
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

    private static long bytes(List<Packet> packets) {
        return packets.stream().mapToLong(Packet::size).sum();
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("the presets hold the documented usage, speed and rate settings")
        void presets() {
            assertEquals(Av1EncoderUsage.REALTIME, Av1EncoderOptions.defaults().usage());
            assertEquals(Av1EncoderUsage.REALTIME, Av1EncoderOptions.forRealtime().usage());
            assertEquals(Av1EncoderUsage.ALL_INTRA, Av1EncoderOptions.forIntraOnly(50).usage());
            var quality = Av1EncoderOptions.ofQuality(60);
            assertEquals(Av1EncoderUsage.GOOD_QUALITY, quality.usage());
            assertEquals(Av1EncoderSpeed.SLOW, quality.speed());
            assertEquals(OptionalInt.of(60), quality.quality());
            assertEquals(35, quality.lookahead());
            var bitrate = Av1EncoderOptions.ofBitrate(800_000);
            assertEquals(Av1EncoderUsage.GOOD_QUALITY, bitrate.usage());
            assertEquals(OptionalInt.empty(), bitrate.quality());
            assertEquals(800_000, bitrate.bitrate());
            var archive = Av1EncoderOptions.forArchive();
            assertEquals(Av1EncoderSpeed.SLOWER, archive.speed());
            assertEquals(OptionalInt.of(75), archive.quality());
            var changed = quality.toBuilder().lookahead(12).speed(Av1EncoderSpeed.SLOWEST).build();
            assertEquals(12, changed.lookahead());
            assertEquals(changed, changed.toBuilder().build());
            assertEquals(changed.hashCode(), changed.toBuilder().build().hashCode());
            assertEquals(Av1EncoderUsage.GOOD_QUALITY,
                    Av1EncoderOptions.forRealtime().toBuilder().usage(Av1EncoderUsage.GOOD_QUALITY).build().usage());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"SLOWEST, 7, 2, 2", "SLOWER, 7, 3, 4", "SLOW, 7, 4, 5", "BALANCED, 7, 5, 6", "FAST, 8, 6, 8",
                "FASTER, 9, 6, 9", "FASTEST, 10, 6, 10"})
        @DisplayName("each speed names its libaom level in every usage")
        void levels(Av1EncoderSpeed speed, int realtime, int good, int intra) {
            assertEquals(realtime, speed.level());
            assertEquals(realtime, speed.level(Av1EncoderUsage.REALTIME));
            assertEquals(good, speed.level(Av1EncoderUsage.GOOD_QUALITY));
            assertEquals(intra, speed.level(Av1EncoderUsage.ALL_INTRA));
        }

        static Stream<Supplier<Av1EncoderOptions>> invalid() {
            return Stream.of(
                    () -> Av1EncoderOptions.builder().quality(50).build(),
                    () -> Av1EncoderOptions.forIntraOnly(50).toBuilder().automaticQuality().build(),
                    () -> Av1EncoderOptions.ofQuality(50).toBuilder().pictureFormat(ChromaFormat.YUV440, 8).build(),
                    () -> Av1EncoderOptions.ofQuality(50).toBuilder().pictureFormat(ChromaFormat.YUV420, 11).build(),
                    () -> Av1EncoderOptions.ofQuality(50).toBuilder().lookahead(-1).build(),
                    () -> Av1EncoderOptions.ofQuality(50).toBuilder().lookahead(65).build(),
                    () -> Av1EncoderOptions.ofQuality(101));
        }

        @ParameterizedTest
        @MethodSource("invalid")
        @DisplayName("inconsistent usage, quality, format and lookahead settings are rejected")
        void rejects(Supplier<Av1EncoderOptions> options) {
            assertThrows(IllegalArgumentException.class, options::get);
        }
    }

    @Nested
    @DisplayName("encoding")
    class Encoding {
        @ParameterizedTest
        @EnumSource(value = Av1EncoderSpeed.class, names = {"SLOWEST", "SLOW", "BALANCED", "FAST"})
        @DisplayName("every speed releases one packet per picture in display order that decodes with the module and ffmpeg alike")
        void speeds(Av1EncoderSpeed speed) throws Exception {
            var pictures = Vp9TestVideo.synthesize(176, 144, 40);
            try (var encoder = new Av1Encoder(176, 144, Av1EncoderOptions.ofQuality(55).toBuilder().speed(speed)
                    .timeBase(TIME_BASE).build())) {
                var packets = encodeAll(encoder, pictures, 0);
                assertEquals(40, packets.size());
                for (var i = 0; i < packets.size(); i++) {
                    assertEquals(i * 3000L, packets.get(i).pts(), "packet " + i);
                    assertEquals(i == 0, packets.get(i).keyframe(), "packet " + i);
                }
                assertEquals(40, encoder.stats().framesEncoded());
                assertEquals(1, encoder.stats().keyframesEncoded());
                var decoded = decode(encoder, packets, 176, 144);
                assertEquals(40, decoded.size());
                var psnr = psnr(pictures, decoded);
                assertTrue(psnr > 33, "psnr " + psnr);
                if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                    var units = packets.stream().map(Packet::data).toList();
                    var raw = Av1EncTesting.ffmpegDecode(Av1EncTesting.writeTemp(Av1EncTesting.ivf(176, 144, units)));
                    var frameSize = Av1EncTesting.raw(decoded.getFirst()).length;
                    assertEquals(frameSize * decoded.size(), raw.length);
                    for (var i = 0; i < decoded.size(); i++) {
                        assertArrayEquals(Av1EncTesting.raw(decoded.get(i)),
                                Arrays.copyOfRange(raw, i * frameSize, (i + 1) * frameSize), "frame " + i);
                    }
                }
            }
        }

        @Test
        @DisplayName("nothing is released until the lookahead fills, and the frame-level call is refused")
        void lookahead() {
            var pictures = Vp9TestVideo.synthesize(64, 64, 6);
            try (var encoder = new Av1Encoder(64, 64, Av1EncoderOptions.ofQuality(50).toBuilder().lookahead(8)
                    .build())) {
                var packets = new ArrayList<Packet>();
                for (var i = 0; i < 6; i++) {
                    encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
                }
                assertTrue(packets.isEmpty());
                assertThrows(IllegalStateException.class,
                        () -> encoder.encodeFrame(new VideoFrame(pictures.getFirst(), 30_000, 3000, false)));
                assertThrows(IllegalStateException.class, () -> encoder.reconfigure(32, 32));
                assertThrows(IllegalArgumentException.class,
                        () -> encoder.reconfigure(Av1EncoderOptions.ofQuality(50).toBuilder()
                                .usage(Av1EncoderUsage.REALTIME)
                                .automaticQuality().build()));
                encoder.flush(packets::add);
                assertEquals(6, packets.size());
            }
        }

        @Test
        @DisplayName("odd picture sizes and short streams decode")
        void oddSizes() {
            for (var size : new int[][]{{99, 67}, {8, 8}, {130, 18}}) {
                var pictures = Vp9TestVideo.synthesize(size[0], size[1], 3);
                try (var encoder = new Av1Encoder(size[0], size[1], Av1EncoderOptions.ofQuality(60))) {
                    var packets = encodeAll(encoder, pictures, 0);
                    assertEquals(3, packets.size());
                    assertEquals(3, decode(encoder, packets, size[0], size[1]).size());
                }
            }
        }

        @Test
        @DisplayName("key frames of screen content, which the stream's sequence header codes without screen content tools, decode")
        void screenContentKeyFrames() {
            var pictures = new ArrayList<YuvPicture>();
            for (var i = 0; i < 6; i++) {
                var picture = YuvPicture.allocate(320, 240, ChromaFormat.YUV420, false,
                        com.github.auties00.smalt.image.ColorInfo.BT601_LIMITED);
                for (var y = 0; y < 240; y++) {
                    for (var x = 0; x < 320; x++) {
                        var inSquare = x >= 40 + i * 8 && x < 104 + i * 8 && y >= 80 && y < 144;
                        picture.y()[y * picture.yStride() + x] = (byte) (inSquare ? 235 : 16 + (x / 40) * 28);
                    }
                }
                for (var y = 0; y < 120; y++) {
                    for (var x = 0; x < 160; x++) {
                        picture.u()[y * picture.uvStride() + x] = (byte) (64 + (x / 20) * 20);
                        picture.v()[y * picture.uvStride() + x] = (byte) (200 - (x / 20) * 16);
                    }
                }
                pictures.add(picture);
            }
            try (var encoder = new Av1Encoder(320, 240, Av1EncoderOptions.ofBitrate(300_000).toBuilder()
                    .speed(Av1EncoderSpeed.FAST).build())) {
                var packets = encodeAll(encoder, pictures, 0);
                assertEquals(6, packets.size());
                var decoded = decode(encoder, packets, 320, 240);
                assertEquals(6, decoded.size());
                assertTrue(psnr(pictures, decoded) > 30, "PSNR " + psnr(pictures, decoded));
            }
        }

        @Test
        @DisplayName("a requested key frame and the key frame interval place key frames")
        void keyFrames() {
            var pictures = Vp9TestVideo.synthesize(96, 64, 30);
            try (var encoder = new Av1Encoder(96, 64, Av1EncoderOptions.ofQuality(50)
                    .toBuilder()
                    .keyframeInterval(20)
                    .build())) {
                var packets = new ArrayList<Packet>();
                for (var i = 0; i < pictures.size(); i++) {
                    if (i == 7) {
                        encoder.requestKeyframe();
                    }
                    encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
                }
                encoder.flush(packets::add);
                assertEquals(30, packets.size());
                for (var i = 0; i < packets.size(); i++) {
                    assertEquals(i == 0 || i == 7 || i == 27, packets.get(i).keyframe(), "packet " + i);
                }
                assertEquals(30, decode(encoder, packets, 96, 64).size());
            }
        }

        @Test
        @DisplayName("a flushed encoder continues the stream with later pictures")
        void flushThenContinue() {
            var pictures = Vp9TestVideo.synthesize(96, 64, 20);
            try (var encoder = new Av1Encoder(96, 64, Av1EncoderOptions.ofQuality(50))) {
                var packets = new ArrayList<>(encodeAll(encoder, pictures.subList(0, 9), 0));
                assertEquals(9, packets.size());
                packets.addAll(encodeAll(encoder, pictures.subList(9, 20), 9));
                assertEquals(20, packets.size());
                for (var i = 0; i < packets.size(); i++) {
                    assertEquals(i * 3000L, packets.get(i).pts(), "packet " + i);
                }
                var decoded = decode(encoder, packets, 96, 64);
                assertEquals(20, decoded.size());
                assertTrue(psnr(pictures, decoded) > 33);
            }
        }

        @Test
        @DisplayName("a higher constant quality spends more bits for a higher fidelity")
        void constantQuality() {
            var pictures = Vp9TestVideo.synthesize(176, 144, 20);
            var sizes = new long[2];
            var fidelity = new double[2];
            var qualities = new int[]{30, 80};
            for (var k = 0; k < 2; k++) {
                try (var encoder = new Av1Encoder(176, 144, Av1EncoderOptions.ofQuality(qualities[k])
                        .toBuilder()
                        .speed(Av1EncoderSpeed.FAST)
                        .build())) {
                    var packets = encodeAll(encoder, pictures, 0);
                    sizes[k] = bytes(packets);
                    fidelity[k] = psnr(pictures, decode(encoder, packets, 176, 144));
                }
            }
            assertTrue(sizes[1] > sizes[0] * 2, Arrays.toString(sizes));
            assertTrue(fidelity[1] > fidelity[0] + 3, Arrays.toString(fidelity));
        }

        @ParameterizedTest
        @CsvSource({"150000", "600000"})
        @DisplayName("a variable bitrate stream lands near its target")
        void variableBitrate(long bitrate) {
            var pictures = Vp9TestVideo.synthesize(176, 144, 90);
            try (var encoder = new Av1Encoder(176, 144, Av1EncoderOptions.ofBitrate(bitrate)
                    .toBuilder()
                    .speed(Av1EncoderSpeed.FAST).frameRate(new Rational(30, 1))
                    .build())) {
                var packets = encodeAll(encoder, pictures, 0);
                var rate = bytes(packets) * 8.0 * 30 / packets.size();
                assertTrue(rate > bitrate * 0.7 && rate < bitrate * 1.3, rate + " bits per second");
                assertEquals(90, decode(encoder, packets, 176, 144).size());
            }
        }

        @Test
        @DisplayName("the stream does not depend on the thread count")
        void threads() {
            var pictures = Vp9TestVideo.synthesize(352, 160, 12);
            for (var tiles = 0; tiles <= 1; tiles++) {
                List<Packet> one;
                try (var encoder = new Av1Encoder(352, 160, Av1EncoderOptions.ofQuality(55).toBuilder().threads(1)
                        .tileColumnsLog2(tiles).build())) {
                    one = encodeAll(encoder, pictures, 0);
                }
                List<Packet> many;
                try (var encoder = new Av1Encoder(352, 160, Av1EncoderOptions.ofQuality(55).toBuilder().threads(4)
                        .tileColumnsLog2(tiles).build())) {
                    many = encodeAll(encoder, pictures, 0);
                }
                assertEquals(one.size(), many.size());
                for (var i = 0; i < one.size(); i++) {
                    assertArrayEquals(one.get(i).data(), many.get(i).data(), "tiles " + tiles + " packet " + i);
                }
            }
        }

        @Test
        @DisplayName("pictures in other formats are converted to 8-bit 4:2:0")
        void converts() {
            var pictures = Vp9TestVideo.synthesize(64, 48, 4).stream()
                    .map(p -> ImageOps.toYuv(p, ChromaFormat.YUV444,
                            p.color()))
                    .toList();
            try (var encoder = new Av1Encoder(64, 48, Av1EncoderOptions.ofQuality(60))) {
                var packets = encodeAll(encoder, pictures, 0);
                assertEquals(4, packets.size());
                assertFalse(decode(encoder, packets, 64, 48).isEmpty());
            }
        }
    }
}
