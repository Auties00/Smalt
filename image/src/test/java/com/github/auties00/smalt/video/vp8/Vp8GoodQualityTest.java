package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.video.VideoFrame;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the good-quality usage of the public VP8 encoder: one-pass and two-pass encodes at a
 * variable bitrate, constrained quality and constant quality, the packets the lookahead releases
 * (shown frames in display order, hidden alternate references in packets of their own flagged
 * {@link PacketFlag#DISCARD}), their decoding with the module's decoder and with FFmpeg, the
 * bitrate accuracy, the monotony of the quality levels, and the independence of the stream from
 * the thread count.
 */
@DisplayName("VP8 good-quality encoding")
class Vp8GoodQualityTest {
    static final int WIDTH = 176;

    static final int HEIGHT = 144;

    static final int FRAMES = 40;

    @TempDir
    Path temp;

    static List<YuvPicture> clip(int frames) {
        return RtTestVideo.synthesize(WIDTH, HEIGHT, frames);
    }

    static List<Packet> encode(Vp8EncoderOptions options, List<YuvPicture> pictures) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new Vp8Encoder(WIDTH, HEIGHT, options)) {
            for (var i = 0; i < pictures.size(); i++) {
                encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
            }
            encoder.flush(packets::add);
        }
        return packets;
    }

    static Vp8FirstPassStats firstPass(Vp8EncoderOptions options, List<YuvPicture> pictures) {
        var pass = new Vp8FirstPass(WIDTH, HEIGHT, options);
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

    static List<Packet> shown(List<Packet> packets) {
        return packets.stream().filter(p -> !p.flags().contains(PacketFlag.DISCARD)).toList();
    }

    static List<byte[]> decode(List<Packet> packets) {
        var out = new ArrayList<byte[]>();
        try (var decoder = new Vp8Decoder(Vp8ConformanceTest.track(WIDTH, HEIGHT))) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> out.add(Ivf.i420((YuvPicture) frame.picture())));
            }
        }
        return out;
    }

    static double psnr(List<YuvPicture> source, List<byte[]> decoded) {
        var total = 0.0;
        for (var i = 0; i < decoded.size(); i++) {
            var s = source.get(i);
            var d = decoded.get(i);
            long sse = 0;
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    var e = (s.y()[y * s.yStride() + x] & 0xFF) - (d[y * WIDTH + x] & 0xFF);
                    sse += e * e;
                }
            }
            total += sse == 0 ? 99 : 10 * Math.log10(255.0 * 255 * WIDTH * HEIGHT / sse);
        }
        return total / decoded.size();
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("the presets select the good-quality usage and its rate controls")
        void presets() {
            var bitrate = Vp8EncoderOptions.ofBitrate(400_000);
            assertEquals(Vp8EncoderUsage.GOOD_QUALITY, bitrate.usage());
            assertEquals(Vp8RateControl.VARIABLE_BITRATE, bitrate.rateControl());
            assertEquals(Vp8EncoderSpeed.SPEED_2, bitrate.speed());
            assertEquals(25, bitrate.lookahead());
            assertTrue(bitrate.alternateReferences());
            var quality = Vp8EncoderOptions.ofQuality(30);
            assertEquals(Vp8RateControl.CONSTANT_QUALITY, quality.rateControl());
            assertEquals(30, quality.cqLevel());
            var archive = Vp8EncoderOptions.forArchive();
            assertEquals(Vp8EncoderSpeed.SPEED_1, archive.speed());
            assertEquals(10, archive.cqLevel());
            assertEquals(Vp8EncoderUsage.REALTIME, Vp8EncoderOptions.defaults().usage());
        }

        @Test
        @DisplayName("each usage rejects the settings of the other")
        void combinations() {
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderOptions.builder()
                    .rateControl(Vp8RateControl.VARIABLE_BITRATE).build());
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderOptions.builder()
                    .speed(Vp8EncoderSpeed.SPEED_2).build());
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderOptions.ofBitrate(300_000).toBuilder()
                    .speed(Vp8EncoderSpeed.SPEED_6).build());
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderOptions.ofBitrate(300_000).toBuilder()
                    .rateControl(Vp8RateControl.CONSTANT_BITRATE).build());
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderOptions.ofBitrate(300_000).toBuilder()
                    .errorResilient(true).build());
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderOptions.ofBitrate(300_000).toBuilder()
                    .temporalLayers(2).build());
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderOptions.ofBitrate(300_000).toBuilder()
                    .lookahead(26).build());
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderOptions.ofBitrate(300_000).toBuilder()
                    .temporalFilter(7, 7).build());
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderOptions.ofQuality(2));
            var stats = firstPass(Vp8EncoderOptions.ofBitrate(300_000), clip(2));
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderOptions.builder().firstPassStats(stats).build());
            assertEquals(Vp8EncoderSpeed.SPEED_4, Vp8EncoderSpeed.of(4));
            assertTrue(Vp8EncoderSpeed.SPEED_4.realtime() && Vp8EncoderSpeed.SPEED_4.goodQuality());
            assertFalse(Vp8EncoderSpeed.SPEED_3.realtime());
            assertFalse(Vp8EncoderSpeed.SPEED_5.goodQuality());
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderSpeed.of(-1));
        }

        @Test
        @DisplayName("first-pass statistics of another picture size are rejected")
        void statsSize() {
            var options = Vp8EncoderOptions.ofBitrate(300_000);
            var stats = firstPass(options, clip(3));
            var second = options.toBuilder().firstPassStats(stats).build();
            assertThrows(IllegalArgumentException.class, () -> new Vp8Encoder(WIDTH + 16, HEIGHT, second));
        }

        @Test
        @DisplayName("encodeFrame refuses the good-quality usage, which holds frames back")
        void encodeFrame() {
            try (var encoder = new Vp8Encoder(WIDTH, HEIGHT, Vp8EncoderOptions.ofQuality(30))) {
                var frame = new VideoFrame(clip(1).getFirst(), 0, 3000, false);
                assertThrows(IllegalStateException.class, () -> encoder.encodeFrame(frame));
            }
        }

        @Test
        @DisplayName("a running good-quality encoder changes only its bitrate, quantizers and quality level")
        void reconfigure() {
            try (var encoder = new Vp8Encoder(WIDTH, HEIGHT, Vp8EncoderOptions.ofBitrate(300_000))) {
                encoder.setTargetBitrate(500_000);
                assertEquals(500_000, encoder.options().bitrate());
                encoder.reconfigure(encoder.options().toBuilder().quantizers(4, 50).cqLevel(20).build());
                assertThrows(IllegalArgumentException.class,
                        () -> encoder.reconfigure(encoder.options().toBuilder().lookahead(10).build()));
                assertThrows(IllegalArgumentException.class,
                        () -> encoder.reconfigure(encoder.options().toBuilder().speed(Vp8EncoderSpeed.SPEED_3).build()));
                assertThrows(IllegalArgumentException.class, () -> encoder.reconfigure(Vp8EncoderOptions.defaults()));
            }
        }

        @Test
        @DisplayName("the best-quality usage takes the rate controls and speeds of the good-quality usage")
        void bestQuality() {
            var best = Vp8EncoderOptions.ofQuality(30).toBuilder().usage(Vp8EncoderUsage.BEST_QUALITY).build();
            assertEquals(Vp8EncoderUsage.BEST_QUALITY, best.usage());
            assertThrows(IllegalArgumentException.class,
                    () -> best.toBuilder().rateControl(Vp8RateControl.CONSTANT_BITRATE).build());
            assertThrows(IllegalArgumentException.class, () -> best.toBuilder().speed(Vp8EncoderSpeed.SPEED_6).build());
            assertThrows(IllegalArgumentException.class, () -> best.toBuilder().dropFrames(true).build());
            try (var encoder = new Vp8Encoder(WIDTH, HEIGHT, best)) {
                assertThrows(IllegalArgumentException.class, () -> encoder.reconfigure(best.toBuilder()
                        .usage(Vp8EncoderUsage.GOOD_QUALITY).build()));
                assertThrows(IllegalStateException.class, () -> encoder.encodeFrame(
                        new VideoFrame(clip(1).getFirst(), 0, 3000, false)));
            }
        }

        @Test
        @DisplayName("first-pass statistics survive serialization and reject malformed data")
        void serialization() {
            var stats = firstPass(Vp8EncoderOptions.ofBitrate(300_000), clip(5));
            var bytes = stats.toByteArray();
            var restored = Vp8FirstPassStats.fromByteArray(bytes);
            assertEquals(stats, restored);
            assertEquals(5, restored.frameCount());
            assertArrayEquals(bytes, restored.toByteArray());
            assertThrows(MediaException.Malformed.class,
                    () -> Vp8FirstPassStats.fromByteArray(java.util.Arrays.copyOf(bytes, bytes.length - 1)));
            var corrupt = bytes.clone();
            corrupt[0] ^= 1;
            assertThrows(MediaException.Malformed.class, () -> Vp8FirstPassStats.fromByteArray(corrupt));
            var truncated = new byte[8];
            assertThrows(MediaException.Malformed.class, () -> Vp8FirstPassStats.fromByteArray(truncated));
        }
    }

    @Nested
    @DisplayName("encoding")
    class Encoding {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Vp8EncoderSpeed.class, names = {"SPEED_0", "SPEED_1", "SPEED_2", "SPEED_3", "SPEED_4"})
        @DisplayName("every speed codes the shown frames in order with hidden alternate references")
        void speeds(Vp8EncoderSpeed speed) {
            var pictures = clip(FRAMES);
            var packets = encode(Vp8EncoderOptions.ofQuality(32).toBuilder().speed(speed).build(), pictures);
            var shown = shown(packets);
            assertEquals(FRAMES, shown.size());
            assertTrue(packets.getFirst().keyframe());
            assertTrue(packets.size() > shown.size(), "no alternate reference");
            for (var i = 0; i < shown.size(); i++) {
                assertEquals(i * 3000L, shown.get(i).pts());
            }
            for (var i = 0; i < packets.size(); i++) {
                var packet = packets.get(i);
                if (packet.flags().contains(PacketFlag.DISCARD)) {
                    assertEquals(0, packet.duration());
                    assertFalse(packet.keyframe());
                    assertEquals(packets.get(i + 1).pts(), packet.pts(), "a hidden frame precedes its shown frame");
                }
            }
            var decoded = decode(packets);
            assertEquals(FRAMES, decoded.size());
            var psnr = psnr(pictures, decoded);
            assertTrue(psnr > 30, "psnr " + psnr);
        }

        @Test
        @DisplayName("the best quality codes a decodable stream of its own, with hidden alternate references, whatever the speed")
        void bestQuality() {
            var pictures = clip(24);
            var good = Vp8EncoderOptions.ofQuality(32).toBuilder().speed(Vp8EncoderSpeed.SPEED_0).build();
            var best = good.toBuilder().usage(Vp8EncoderUsage.BEST_QUALITY).build();
            var bestPackets = encode(best, pictures);
            var shown = shown(bestPackets);
            assertEquals(pictures.size(), shown.size());
            assertTrue(bestPackets.size() > shown.size(), "no alternate reference");
            var decoded = decode(bestPackets);
            assertEquals(pictures.size(), decoded.size());
            var psnr = psnr(pictures, decoded);
            assertTrue(psnr > 30, "psnr " + psnr);
            var goodPackets = encode(good, pictures);
            var differs = goodPackets.size() != bestPackets.size();
            for (var i = 0; i < bestPackets.size() && !differs; i++) {
                differs = !java.util.Arrays.equals(bestPackets.get(i).data(), goodPackets.get(i).data());
            }
            assertTrue(differs, "the best quality codes the stream of speed 0");
            // libvpx ignores cpu-used at its best quality
            var fast = encode(best.toBuilder().speed(Vp8EncoderSpeed.SPEED_4).build(), pictures);
            assertEquals(bestPackets.size(), fast.size());
            for (var i = 0; i < fast.size(); i++) {
                assertArrayEquals(bestPackets.get(i).data(), fast.get(i).data(), "packet " + i);
            }
        }

        @Test
        @DisplayName("finer quality levels code larger, closer streams")
        void qualityLevels() {
            var pictures = clip(24);
            var coarse = encode(Vp8EncoderOptions.ofQuality(50).toBuilder().speed(Vp8EncoderSpeed.SPEED_3).build(),
                    pictures);
            var fine = encode(Vp8EncoderOptions.ofQuality(20).toBuilder().speed(Vp8EncoderSpeed.SPEED_3).build(),
                    pictures);
            assertTrue(bytes(fine) > bytes(coarse), bytes(fine) + " <= " + bytes(coarse));
            assertTrue(psnr(pictures, decode(fine)) > psnr(pictures, decode(coarse)));
        }

        @ParameterizedTest(name = "{0} kbit/s, two pass {1}, speed {2}")
        @CsvSource({"150, false, 4", "400, false, 4", "150, true, 4", "400, true, 2", "400, false, 1"})
        @DisplayName("the variable bitrate meets its target over the clip")
        void bitrate(int kbit, boolean twoPass, int speed) {
            var pictures = clip(60);
            var options = Vp8EncoderOptions.ofBitrate(kbit * 1000L).toBuilder().speed(Vp8EncoderSpeed.of(speed)).build();
            if (twoPass) {
                options = options.toBuilder().firstPassStats(firstPass(options, pictures)).build();
            }
            var packets = encode(options, pictures);
            var actual = kbps(packets, pictures.size());
            assertTrue(Math.abs(actual - kbit) < kbit * 0.25, "coded " + actual + " kbit/s for " + kbit);
            assertEquals(pictures.size(), decode(packets).size());
        }

        @Test
        @DisplayName("constrained quality stays below the bitrate")
        void constrainedQuality() {
            var pictures = clip(30);
            var options = Vp8EncoderOptions.ofBitrate(2_000_000).toBuilder()
                    .rateControl(Vp8RateControl.CONSTRAINED_QUALITY).cqLevel(40).speed(Vp8EncoderSpeed.SPEED_3).build();
            var packets = encode(options, pictures);
            assertTrue(kbps(packets, pictures.size()) < 2000);
            assertEquals(pictures.size(), decode(packets).size());
        }

        @Test
        @DisplayName("unfiltered and disabled alternate references decode")
        void alternateReferences() {
            var pictures = clip(30);
            var unfiltered = encode(Vp8EncoderOptions.ofQuality(30).toBuilder().temporalFilter(0, 0).build(), pictures);
            assertEquals(pictures.size(), decode(unfiltered).size());
            assertTrue(unfiltered.size() > pictures.size());
            var none = encode(Vp8EncoderOptions.ofQuality(30).toBuilder().alternateReferences(false).build(), pictures);
            assertEquals(pictures.size(), none.size());
            assertEquals(pictures.size(), decode(none).size());
        }

        @Test
        @DisplayName("a two-pass encode is deterministic and independent of the thread count")
        void deterministic() {
            var pictures = clip(30);
            var options = Vp8EncoderOptions.ofBitrate(300_000).toBuilder().speed(Vp8EncoderSpeed.SPEED_2).threads(1)
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
            var options = Vp8EncoderOptions.ofBitrate(300_000);
            options = options.toBuilder().firstPassStats(firstPass(options, pictures.subList(0, 3))).build();
            var encoder = new Vp8Encoder(WIDTH, HEIGHT, options);
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
            try (var encoder = new Vp8Encoder(WIDTH, HEIGHT, Vp8EncoderOptions.ofQuality(36).toBuilder()
                    .speed(Vp8EncoderSpeed.SPEED_4).build())) {
                for (var i = 0; i < pictures.size(); i++) {
                    if (i == 11) {
                        encoder.requestKeyframe();
                    }
                    encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
                }
                encoder.flush(packets::add);
                var stats = encoder.stats();
                assertEquals(20, stats.framesEncoded());
                assertEquals(bytes(packets), stats.bytesEncoded());
            }
            var shown = shown(packets);
            assertTrue(shown.get(11).keyframe());
            assertFalse(shown.get(10).keyframe());
        }
    }

    @Nested
    @DisplayName("interoperability")
    class Interop {
        @ParameterizedTest(name = "speed {0}")
        @CsvSource({"4", "1"})
        @DisplayName("FFmpeg's libvpx and native decoders reproduce the module decoder's pictures")
        void ffmpeg(int speed) throws Exception {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var pictures = clip(30);
            var options = Vp8EncoderOptions.ofBitrate(400_000).toBuilder().speed(Vp8EncoderSpeed.of(speed)).build();
            var packets = encode(options, pictures);
            var decoded = decode(packets);
            assertEquals(pictures.size(), decoded.size());
            var raw = new ByteArrayOutputStream();
            decoded.forEach(raw::writeBytes);
            var ivf = temp.resolve("good.ivf");
            Files.write(ivf, Ivf.write(WIDTH, HEIGHT, packets.stream().map(Packet::data).toList()));
            var want = Ivf.md5(raw.toByteArray());
            assertEquals(want, Ivf.md5(Vp8DifferentialTest.ffmpegDecode(ivf, "libvpx")), "libvpx");
            assertEquals(want, Ivf.md5(Vp8DifferentialTest.ffmpegDecode(ivf, "vp8")), "ffmpeg vp8");
        }
    }
}
