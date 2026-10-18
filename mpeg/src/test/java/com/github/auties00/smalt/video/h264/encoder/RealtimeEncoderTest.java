package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;
import com.github.auties00.smalt.video.h264.encoder.H264PacketFormat;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the real-time behavior calls rely on: the Annex B wire contract, live bitrate changes,
 * key frame requests from another thread, the color description, lenient timestamps, the
 * quantizer window and the key frame size cap.
 */
@DisplayName("H264Encoder in real-time mode")
class RealtimeEncoderTest {
    private static final Rational FPS = new Rational(30, 1);

    // the real-time IDR size limits of the encoder's rate control, in frame budgets
    private static final int FIRST_KEYFRAME_BUDGET = 5;

    private static final int KEYFRAME_BUDGET = 7;

    private static final VideoTrack ANNEX_B = VideoTrack.builder().id(1).codec(Codec.H264)
            .timeBase(new Rational(1, 90_000))
            .build();

    /** Splits an Annex B access unit at its four-byte start codes and returns the NAL unit types. */
    static List<Integer> nalTypes(byte[] unit) {
        var types = new ArrayList<Integer>();
        assertTrue(unit.length > 5 && unit[0] == 0 && unit[1] == 0 && unit[2] == 0 && unit[3] == 1,
                "the packet starts with a four-byte start code");
        for (var i = 0; i + 4 < unit.length; i++) {
            if (unit[i] == 0 && unit[i + 1] == 0 && unit[i + 2] == 1) {
                assertEquals(0, unit[i - 1], "every start code has four bytes");
                types.add(unit[i + 3] & 0x1F);
            }
        }
        return types;
    }

    private static VideoFrame frame(YuvPicture picture, long pts) {
        return new VideoFrame(picture, pts, 0, false);
    }

    @Nested
    @DisplayName("Annex B wire contract")
    class AnnexB {
        @Test
        @DisplayName("every packet is an access unit with 4-byte start codes, and every IDR, requested ones included, starts with SPS and PPS")
        void framing() {
            var config = H264Setup.realtime(320, 240, FPS, 300_000).withKeyframeInterval(10);
            var packets = new ArrayList<Packet>();
            try (var encoder = config.encoder()) {
                assertTrue(encoder.codecConfig().isEmpty(), "parameter sets travel in band");
                for (var i = 0; i < 30; i++) {
                    if (i == 15) {
                        encoder.requestKeyframe();
                    }
                    encoder.encode(frame(EncoderHarness.synthetic(320, 240, i, 3, 2.0), i * 3000L), packets::add);
                }
            }
            var idrs = new ArrayList<Integer>();
            for (var i = 0; i < packets.size(); i++) {
                var packet = packets.get(i);
                var types = nalTypes(packet.data());
                if (packet.keyframe()) {
                    idrs.add(i);
                    assertEquals(List.of(7, 8, 5), types, "IDR access unit " + i);
                } else {
                    assertEquals(List.of(1), types, "P access unit " + i);
                }
            }
            assertEquals(List.of(0, 10, 15, 25), idrs);
        }

        @Test
        @DisplayName("ffmpeg and Smalt decode the stream to exactly the encoder's reconstruction")
        void bitExact() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var config = H264Setup.realtime(352, 288, FPS, 400_000).withKeyframeInterval(12);
            var stream = new ByteArrayOutputStream();
            var units = new ArrayList<byte[]>();
            var reconstructions = new ArrayList<byte[]>();
            try (var encoder = config.encoder()) {
                for (var i = 0; i < 30; i++) {
                    if (i == 7) {
                        encoder.requestKeyframe();
                    }
                    encoder.encode(frame(EncoderHarness.synthetic(352, 288, i, 9, 3.0), i * 3000L), packet -> {
                        stream.writeBytes(packet.data());
                        units.add(packet.data());
                    });
                    reconstructions.add(encoder.core().lastReconstructionI420());
                }
            }
            var decoded = EncoderHarness.decodeStrict(EncoderHarness.write("realtime-annexb.264", stream.toByteArray()));
            var size = reconstructions.getFirst().length;
            assertEquals(size * reconstructions.size(), decoded.length);
            for (var i = 0; i < reconstructions.size(); i++) {
                assertArrayEquals(reconstructions.get(i), Arrays.copyOfRange(decoded, i * size, (i + 1) * size),
                        "ffmpeg frame " + i);
            }
            try (var decoder = new H264Decoder(ANNEX_B, H264DecoderOptions.forRealtime())) {
                for (var i = 0; i < units.size(); i++) {
                    var index = i;
                    var out = new int[1];
                    decoder.decode(new Packet(1, units.get(i), i * 3000L, i * 3000L, 3000, false), (f, damaged) -> {
                        assertFalse(damaged);
                        var picture = (YuvPicture) f.picture();
                        var samples = new byte[size];
                        var w = picture.width();
                        var h = picture.height();
                        for (var y = 0; y < h; y++) {
                            System.arraycopy(picture.y(), y * picture.yStride(), samples, y * w, w);
                        }
                        for (var y = 0; y < h / 2; y++) {
                            System.arraycopy(picture.u(), y * picture.uvStride(), samples, w * h + y * w / 2, w / 2);
                            System.arraycopy(picture.v(), y * picture.uvStride(), samples, w * h * 5 / 4 + y * w / 2, w / 2);
                        }
                        assertArrayEquals(reconstructions.get(index), samples, "Smalt frame " + index);
                        out[0]++;
                    });
                    assertEquals(1, out[0], "one frame per access unit, before decode returns");
                }
            }
        }

        @Test
        @DisplayName("the stream signals Constrained Baseline, POC type 2, no reordering and BT.601 limited color")
        void streamProperties() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var config = H264Setup.realtime(640, 480, FPS, 600_000);
            var stream = new ByteArrayOutputStream();
            try (var encoder = config.encoder()) {
                for (var i = 0; i < 3; i++) {
                    encoder.encode(frame(EncoderHarness.synthetic(640, 480, i, 1, 1.0), i * 3000L),
                            packet -> stream.writeBytes(packet.data()));
                }
            }
            var headers = EncoderHarness.traceHeaders(EncoderHarness.write("realtime-props.264", stream.toByteArray()));
            assertEquals(66L, headers.get("profile_idc"));
            assertEquals(1L, headers.get("constraint_set0_flag"));
            assertEquals(1L, headers.get("constraint_set1_flag"));
            assertEquals(30L, headers.get("level_idc"));
            assertEquals(2L, headers.get("pic_order_cnt_type"));
            assertEquals(0L, headers.get("entropy_coding_mode_flag"));
            assertEquals(1L, headers.get("bitstream_restriction_flag"));
            assertEquals(0L, headers.get("max_num_reorder_frames"));
            assertEquals(6L, headers.get("colour_primaries"));
            assertEquals(6L, headers.get("transfer_characteristics"));
            assertEquals(6L, headers.get("matrix_coefficients"));
            assertEquals(0L, headers.get("video_full_range_flag"));
        }

        @Test
        @DisplayName("RGB input is converted with the BT.601 limited-range matrix")
        void rgbConversion() {
            var width = 64;
            var height = 64;
            var pixels = new byte[width * height * 4];
            for (var i = 0; i < width * height; i++) {
                pixels[4 * i] = (byte) 255;
                pixels[4 * i + 3] = (byte) 255;
            }
            var config = H264Setup.realtime(width, height, FPS, 200_000);
            try (var encoder = config.encoder()) {
                encoder.encode(new VideoFrame(RgbaPicture.of(width, height, width * 4, pixels, false), 0, 0, false),
                        packet -> {
                        });
                var reconstruction = encoder.core().lastReconstructionI420();
                assertEquals(81, reconstruction[width * height / 2 + width / 2] & 0xFF, 2.0, "red luma");
                assertEquals(90, reconstruction[width * height + width * height / 8] & 0xFF, 2.0, "red Cb");
                assertEquals(240, reconstruction[width * height * 5 / 4 + width * height / 8] & 0xFF, 2.0, "red Cr");
            }
        }
    }

    @Nested
    @DisplayName("live bitrate changes")
    class Bitrate {
        @Test
        @DisplayName("steps 350k -> 1.02M -> 128k -> 600k are tracked within 5% every second after the first, without skipping frames")
        void steps() {
            var schedule = new int[]{350_000, 1_020_000, 128_000, 600_000};
            var seconds = 3;
            // 480x360 synthetic content stays inside the quantizer window at every rate of the schedule
            var config = H264Setup.realtime(480, 360, FPS, schedule[0]);
            var bits = new long[schedule.length * seconds];
            try (var encoder = config.encoder()) {
                for (var i = 0; i < schedule.length * seconds * 30; i++) {
                    if (i % (seconds * 30) == 0) {
                        encoder.setTargetBitrate(schedule[i / (seconds * 30)]);
                    }
                    var second = i / 30;
                    encoder.encode(frame(EncoderHarness.synthetic(480, 360, i, 5, 1.0), i * 3000L), packet -> {
                        assertTrue(packet.size() > 0, "no frame is skipped");
                        bits[second] += packet.size() * 8L;
                    });
                }
                assertEquals(schedule[schedule.length - 1], encoder.stats().targetBitrate());
                assertEquals(schedule.length * seconds * 30, encoder.stats().framesEncoded());
            }
            var report = new StringBuilder();
            var worst = 0.0;
            for (var second = 1; second < bits.length; second++) {
                var target = schedule[second / seconds];
                var deviation = (bits[second] - target) / (double) target;
                report.append(String.format("%d:%+.1f%% ", second, 100 * deviation));
                worst = Math.max(worst, Math.abs(deviation));
            }
            assertTrue(worst <= 0.05, "per-second deviation " + report);
        }

        @Test
        @DisplayName("rejects non-positive targets and constant quality encoders")
        void validation() {
            try (var encoder = H264Setup.realtime(64, 64, FPS, 100_000).encoder()) {
                assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(0));
            }
            var quality = H264Setup.of(64, 64, FPS, new Rational(1, 30),
                    H264Profile.MAIN, 0, H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 26, 0,
                    H264EncoderSpeed.FAST, ColorInfo.BT709_LIMITED, 1);
            try (var encoder = quality.encoder()) {
                assertThrows(IllegalStateException.class, () -> encoder.setTargetBitrate(100_000));
            }
        }

        @Test
        @DisplayName("a target above the level's maximum is capped to it")
        void levelCap() {
            try (var encoder = H264Setup.realtime(176, 144, FPS, 60_000).encoder()) {
                encoder.setTargetBitrate(50_000_000);
                encoder.encode(frame(EncoderHarness.synthetic(176, 144, 0, 1, 1.0), 0), packet -> {
                });
                assertTrue(encoder.stats().targetBitrate() < 50_000_000);
            }
        }
    }

    @Nested
    @DisplayName("key frame requests")
    class Keyframes {
        @Test
        @DisplayName("requests from another thread while frames are encoded are never lost")
        void asynchronous() throws InterruptedException {
            var frames = 900;
            var config = H264Setup.realtime(320, 240, FPS, 300_000);
            var starts = new long[frames];
            var ends = new long[frames];
            var idr = new boolean[frames];
            var requests = new ConcurrentLinkedQueue<Long>();
            var running = new AtomicBoolean(true);
            try (var encoder = config.encoder()) {
                var requester = Thread.ofPlatform().start(() -> {
                    var random = new Random(7);
                    while (running.get()) {
                        var until = System.nanoTime() + 1_000_000L + random.nextInt(5_000_000);
                        while (System.nanoTime() < until) {
                            Thread.onSpinWait();
                        }
                        requests.add(System.nanoTime());
                        encoder.requestKeyframe();
                    }
                });
                for (var i = 0; i < frames; i++) {
                    var index = i;
                    starts[i] = System.nanoTime();
                    encoder.encode(frame(EncoderHarness.synthetic(320, 240, i, 2, 2.0), i * 3000L),
                            packet -> idr[index] = packet.keyframe());
                    ends[i] = System.nanoTime();
                }
                running.set(false);
                requester.join();
            }
            var total = 0;
            var lost = 0;
            for (var request : requests) {
                var k = 0;
                while (k < frames && ends[k] < request) {
                    k++;
                }
                if (k + 1 >= frames) {
                    continue;
                }
                total++;
                if (!idr[k] && !idr[k + 1]) {
                    lost++;
                }
            }
            assertTrue(total >= 75, "only " + total + " requests were made");
            assertEquals(0, lost, "requests lost of " + total);
        }
    }

    @Nested
    @DisplayName("timestamps")
    class Timestamps {
        @Test
        @DisplayName("real-time mode accepts timestamps that do not increase and keeps packet timestamps increasing")
        void lenient() {
            var inputs = new long[]{0, 3000, 3000, 2000, Packet.NO_TIMESTAMP, 9000};
            var outputs = new ArrayList<Long>();
            try (var encoder = H264Setup.realtime(64, 64, FPS, 100_000).encoder()) {
                for (var i = 0; i < inputs.length; i++) {
                    encoder.encode(frame(EncoderHarness.synthetic(64, 64, i, 1, 1.0), inputs[i]),
                            packet -> outputs.add(packet.pts()));
                }
            }
            assertEquals(List.of(0L, 3000L, 3001L, 3002L, 6002L, 9000L), outputs);
        }

        @Test
        @DisplayName("without timestamps, packets are stamped one nominal frame apart")
        void absent() {
            var outputs = new ArrayList<Long>();
            try (var encoder = H264Setup.realtime(64, 64, FPS, 100_000).encoder()) {
                for (var i = 0; i < 4; i++) {
                    encoder.encode(frame(EncoderHarness.synthetic(64, 64, i, 1, 1.0), Packet.NO_TIMESTAMP),
                            packet -> outputs.add(packet.pts()));
                }
            }
            assertEquals(List.of(0L, 3000L, 6000L, 9000L), outputs);
        }

        @Test
        @DisplayName("outside real-time mode a timestamp that does not increase is rejected")
        void strict() {
            var config = H264Setup.realtime(64, 64, FPS, 100_000).withRealtime(false);
            try (var encoder = config.encoder()) {
                encoder.encode(frame(EncoderHarness.synthetic(64, 64, 0, 1, 1.0), 3000), packet -> {
                });
                assertThrows(IllegalArgumentException.class,
                        () -> encoder.encode(frame(EncoderHarness.synthetic(64, 64, 1, 1, 1.0), 3000), packet -> {
                        }));
            }
        }
    }

    @Nested
    @DisplayName("quantizer window")
    class Window {
        @ParameterizedTest(name = "{0} bps")
        @ValueSource(ints = {20_000, 8_000_000})
        @DisplayName("every frame and macroblock quantizer stays inside the window")
        void bounded(int bitrate) {
            var config = H264Setup.realtime(320, 240, FPS, bitrate).withQuantizerWindow(24, 36)
                    .withKeyframeInterval(15);
            try (var encoder = config.encoder()) {
                for (var i = 0; i < 40; i++) {
                    encoder.encode(frame(EncoderHarness.synthetic(320, 240, i, 4, 4.0), i * 3000L), packet -> {
                    });
                    var qp = encoder.stats().lastQuantizer();
                    assertTrue(qp >= 24 && qp <= 36, "frame " + i + " quantizer " + qp);
                    for (var mbQp : encoder.core().macroblockQuantizers()) {
                        assertTrue(mbQp >= 24 && mbQp <= 36, "frame " + i + " macroblock quantizer " + mbQp);
                    }
                }
            }
        }

        @Test
        @DisplayName("rejects an empty or out-of-range window")
        void validation() {
            var config = H264Setup.realtime(64, 64, FPS, 100_000);
            assertThrows(IllegalArgumentException.class, () -> config.withQuantizerWindow(30, 20));
            assertThrows(IllegalArgumentException.class, () -> config.withQuantizerWindow(-1, 20));
            assertThrows(IllegalArgumentException.class, () -> config.withQuantizerWindow(10, 52));
        }
    }

    @Nested
    @DisplayName("key frame size")
    class KeyframeSize {
        @Test
        @DisplayName("IDR access units approach the real-time cap as the size model learns, and are larger without it")
        void capped() {
            var bitrate = 600_000;
            var cap = KEYFRAME_BUDGET * bitrate / 30.0 / 8;
            var capped = keyframeSizes(H264Setup.realtime(640, 480, FPS, bitrate).withKeyframeInterval(30));
            var uncapped = keyframeSizes(H264Setup.realtime(640, 480, FPS, bitrate).withKeyframeInterval(30)
                    .withRealtime(false));
            // the size model starts generic and learns from every IDR, so only the later IDRs are held to the cap
            for (var i = capped.size() - 2; i < capped.size(); i++) {
                assertTrue(capped.get(i) <= 1.3 * cap, "IDR " + i + " is " + capped.get(i) + " bytes, cap " + cap);
            }
            for (var i = 1; i < capped.size(); i++) {
                assertTrue(capped.get(i) < capped.get(i - 1) * 1.1 || capped.get(i) <= 1.3 * cap,
                        "IDR " + i + " grows away from the cap: " + capped);
            }
            assertTrue(uncapped.subList(1, uncapped.size()).stream().mapToInt(Integer::intValue).sum()
                    > capped.subList(1, capped.size()).stream().mapToInt(Integer::intValue).sum());
        }

        static Stream<Arguments> naturalClips() {
            return Stream.of(Arguments.of("johnny720.yuv", 1280, 720, 1_000_000),
                    Arguments.of("stockholm720.yuv", 1280, 720, 500_000),
                    Arguments.of("kriste480.yuv", 640, 480, 600_000),
                    Arguments.of("stock480.yuv", 640, 480, 350_000));
        }

        // The first IDR cannot be coded again in real-time mode, so its quantizer rests on the
        // intra model alone; with the shared inter exponent these clips came out 1.5 to 2.9 times
        // over the limit.
        @ParameterizedTest(name = "{0} at {3} bps")
        @MethodSource("naturalClips")
        @DisplayName("the first IDR access unit of natural video lands within 40 percent of its budget")
        void firstOnNaturalVideo(String clip, int width, int height, int bitrate) throws IOException {
            var root = Path.of(System.getProperty("smalt.test.clips",
                    Path.of(System.getProperty("user.home"), ".smalt", "cache", "clips").toString()));
            var file = root.resolve(clip);
            Skip.unless(Files.isRegularFile(file), "data:clip", "clip " + clip + " is not cached");
            var luma = width * height;
            var samples = new byte[luma * 3 / 2];
            try (var input = Files.newInputStream(file)) {
                assertEquals(samples.length, input.readNBytes(samples, 0, samples.length));
            }
            var picture = YuvPicture.builder()
                    .size(width, height)
                    .chromaFormat(com.github.auties00.smalt.image.ChromaFormat.YUV420)
                    .color(ColorInfo.BT601_LIMITED)
                    .luma(Arrays.copyOfRange(samples, 0, luma), width)
                    .chroma(Arrays.copyOfRange(samples, luma, luma + luma / 4),
                            Arrays.copyOfRange(samples, luma + luma / 4, samples.length), width / 2)
                    .build();
            var budget = FIRST_KEYFRAME_BUDGET * bitrate / 30.0 / 8;
            var size = new int[1];
            try (var encoder = H264Setup.realtime(width, height, FPS, bitrate).encoder()) {
                encoder.encode(frame(picture, 0), packet -> size[0] = packet.size());
            }
            assertTrue(size[0] >= 0.6 * budget && size[0] <= 1.4 * budget,
                    "first IDR is " + size[0] + " bytes, budget " + budget);
        }

        private static List<Integer> keyframeSizes(H264Setup config) {
            var sizes = new ArrayList<Integer>();
            try (var encoder = config.encoder()) {
                for (var i = 0; i < 150; i++) {
                    encoder.encode(frame(EncoderHarness.synthetic(640, 480, i, 6, 2.0), i * 3000L), packet -> {
                        if (packet.keyframe()) {
                            sizes.add(packet.size());
                        }
                    });
                }
                assertEquals(sizes.size(), encoder.stats().keyframesEncoded());
            }
            return sizes;
        }
    }
}
