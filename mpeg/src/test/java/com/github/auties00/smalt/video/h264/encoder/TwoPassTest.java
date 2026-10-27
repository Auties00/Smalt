package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.encoder.internal.PassRecords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers two-pass average bitrate encoding: the second pass lands within 2% of the target size (the bitrate
 * times the measured duration) over content whose complexity changes several times, in every coding structure
 * and engine; it spends bits more evenly over complexity than a single pass; the first pass sees the picture
 * types the second pass codes; the streams decode to the encoder's reconstruction; and the statistics serialize,
 * reject corrupt input and validate their use.
 */
class TwoPassTest {
    private static final int WIDTH = 192;

    private static final int HEIGHT = 128;

    private static final int FPS = 30;

    /**
     * A clip of 240 frames whose complexity changes: calm motion, heavy noise, an unrelated high-contrast scene
     * and moderate noise, 60 frames each.
     */
    static List<YuvPicture> varied(int width, int height) {
        var pictures = new ArrayList<YuvPicture>();
        for (var i = 0; i < 60; i++) {
            pictures.add(EncoderHarness.synthetic(width, height, i, 3, 0.5));
        }
        for (var i = 0; i < 60; i++) {
            pictures.add(EncoderHarness.synthetic(width, height, 60 + i, 5, 9.0));
        }
        for (var i = 0; i < 60; i++) {
            pictures.add(EncoderHarness.checkerboard(width, height, i));
        }
        for (var i = 0; i < 60; i++) {
            pictures.add(EncoderHarness.synthetic(width, height, 180 + i, 7, 3.0));
        }
        return pictures;
    }

    static H264EncoderOptions.Builder base(long bitrate) {
        return H264EncoderOptions.forFile().toBuilder()
                .frameRate(new Rational(FPS, 1))
                .timeBase(new Rational(1, FPS))
                .bitrate(bitrate);
    }

    /** Runs the first pass over pictures, one frame per time base unit. */
    static H264FirstPassStats firstPass(int width, int height, H264EncoderOptions options,
                                        List<? extends Picture> pictures) {
        var pass = new H264FirstPass(width, height, options);
        for (var i = 0; i < pictures.size(); i++) {
            pass.add(new VideoFrame(pictures.get(i), i, 0, false));
        }
        return pass.finish();
    }

    /** Encodes pictures, one frame per time base unit, and returns the packets. */
    static List<Packet> encode(int width, int height, H264EncoderOptions options, List<? extends Picture> pictures) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new H264Encoder(width, height, options)) {
            for (var i = 0; i < pictures.size(); i++) {
                encoder.encode(new VideoFrame(pictures.get(i), i, 0, false), packets::add);
            }
            encoder.flush(packets::add);
        }
        return packets;
    }

    static long bits(List<Packet> packets) {
        return 8 * packets.stream().mapToLong(Packet::size).sum();
    }

    static double seconds(H264FirstPassStats stats) {
        return stats.duration().toNanos() / 1e9;
    }

    record Case(String name, H264EncoderOptions options) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("file preset 400 kbps", base(400_000).build()),
                new Case("file preset 150 kbps", base(150_000).build()),
                new Case("fast, 3 B pictures, IDR every second, 1.2 Mbps", base(1_200_000)
                        .speed(H264EncoderSpeed.FAST).bFrames(3).lookahead(8).keyframeInterval(FPS).build()),
                new Case("slow, 250 kbps", base(250_000).speed(H264EncoderSpeed.SLOW).build()),
                new Case("no look-ahead, I and P only, 300 kbps", base(300_000).lookahead(0).bFrames(0)
                        .speed(H264EncoderSpeed.FAST).build()),
                new Case("three temporal layers, 300 kbps", base(300_000).temporalLayers(3).build()),
                new Case("high 4:2:2 10-bit, 600 kbps", base(600_000).profile(H264Profile.HIGH_422)
                        .chromaFormat(ChromaFormat.YUV422).bitDepth(10).build()),
                new Case("interlaced field pictures, 500 kbps", base(500_000).profile(H264Profile.HIGH)
                        .fieldOrder(FieldOrder.TOP_FIRST).fieldCoding(H264FieldCoding.FIELD_PICTURES).build()),
                new Case("buffer of a quarter second, 400 kbps", base(400_000)
                        .bufferSize(java.time.Duration.ofMillis(250)).build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("the second pass lands within 2% of the bitrate times the measured duration")
    void targetSize(Case test) {
        var pictures = varied(WIDTH, HEIGHT);
        var stats = firstPass(WIDTH, HEIGHT, test.options(), pictures);
        assertEquals(pictures.size(), stats.frameCount());
        assertEquals(pictures.size() / (double) FPS, seconds(stats), 1e-9);
        var second = test.options().toBuilder().firstPassStats(stats).build();
        var packets = encode(WIDTH, HEIGHT, second, pictures);
        assertEquals(pictures.size(), packets.size());
        var target = test.options().bitrate() * seconds(stats);
        var actual = bits(packets);
        assertTrue(Math.abs(actual - target) <= 0.02 * target,
                test + ": " + actual + " bits for a target of " + target + " ("
                        + String.format("%+.2f%%", 100 * (actual - target) / target) + ")");
    }

    @Test
    @DisplayName("the second pass may change the speed and the bitrate and still lands within 2% of the new target")
    void otherSpeedAndBitrate() {
        var pictures = varied(WIDTH, HEIGHT);
        var first = base(300_000).speed(H264EncoderSpeed.FAST).build();
        var stats = firstPass(WIDTH, HEIGHT, first, pictures);
        var second = first.toBuilder().speed(H264EncoderSpeed.BALANCED).bitrate(700_000).firstPassStats(stats).build();
        var target = 700_000 * seconds(stats);
        var actual = bits(encode(WIDTH, HEIGHT, second, pictures));
        assertTrue(Math.abs(actual - target) <= 0.02 * target, actual + " bits for a target of " + target);
    }

    /** Returns the quantizer of every coded frame by presentation timestamp. */
    static double[] quantizers(H264EncoderOptions options, List<YuvPicture> pictures) {
        var qps = new double[pictures.size()];
        try (var encoder = new H264Encoder(WIDTH, HEIGHT, options)) {
            for (var i = 0; i < pictures.size(); i++) {
                encoder.encode(new VideoFrame(pictures.get(i), i, 0, false),
                        packet -> qps[(int) packet.pts()] = encoder.stats().lastQuantizer());
            }
            encoder.flush(packet -> qps[(int) packet.pts()] = encoder.stats().lastQuantizer());
        }
        return qps;
    }

    static double mean(double[] values, int from, int to) {
        var sum = 0.0;
        for (var i = from; i < to; i++) {
            sum += values[i];
        }
        return sum / (to - from);
    }

    @Test
    @DisplayName("over a calm half and a complex half, the two-pass quantizers differ less than the single-pass ones")
    void spendsOverTheWholeVideo() {
        var pictures = new ArrayList<YuvPicture>();
        for (var i = 0; i < 150; i++) {
            pictures.add(EncoderHarness.synthetic(WIDTH, HEIGHT, i, 3, 0.3));
        }
        for (var i = 0; i < 150; i++) {
            pictures.add(EncoderHarness.synthetic(WIDTH, HEIGHT, 150 + i, 9, 12.0));
        }
        var options = base(250_000).bFrames(0).lookahead(0).speed(H264EncoderSpeed.FAST).build();
        var single = quantizers(options, pictures);
        var stats = firstPass(WIDTH, HEIGHT, options, pictures);
        var dual = quantizers(options.toBuilder().firstPassStats(stats).build(), pictures);
        var singleGap = mean(single, 165, 300) - mean(single, 15, 150);
        var dualGap = mean(dual, 165, 300) - mean(dual, 15, 150);
        assertTrue(singleGap > 0 && dualGap > 0, "single " + singleGap + ", two-pass " + dualGap);
        assertTrue(dualGap < singleGap - 1, "two-pass gap " + dualGap + " is not below the single-pass gap " + singleGap);
    }

    @Test
    @DisplayName("the first pass records the picture types the second pass codes")
    void sameStructure() {
        var pictures = varied(WIDTH, HEIGHT);
        var options = base(400_000).build();
        var stats = firstPass(WIDTH, HEIGHT, options, pictures);
        var records = stats.records();
        var packets = encode(WIDTH, HEIGHT, options.toBuilder().firstPassStats(stats).build(), pictures);
        var types = new int[pictures.size()];
        var latest = -1L;
        for (var packet : packets) {
            var type = packet.keyframe() ? PassRecords.INTRA
                    : packet.pts() < latest ? PassRecords.BIDIRECTIONAL : PassRecords.PREDICTED;
            types[(int) packet.pts()] = type;
            latest = Math.max(latest, packet.pts());
        }
        var counts = new int[3];
        for (var i = 0; i < pictures.size(); i++) {
            assertEquals(records.type(i), types[i], "picture type of frame " + i);
            counts[types[i]]++;
        }
        assertTrue(counts[PassRecords.INTRA] >= 4 && counts[PassRecords.BIDIRECTIONAL] > 100,
                "IDR, P and B pictures " + Arrays.toString(counts));
        assertTrue(packets.get(120).keyframe(), "the scene cut is an IDR picture");
    }

    @Test
    @DisplayName("the statistics and the second pass do not depend on the thread count")
    void threadInvariance() {
        var pictures = varied(WIDTH, HEIGHT).subList(40, 160);
        var options = base(500_000).build();
        var single = firstPass(WIDTH, HEIGHT, options.toBuilder().threads(1).build(), pictures);
        var parallel = firstPass(WIDTH, HEIGHT, options.toBuilder().threads(4).build(), pictures);
        assertEquals(single, parallel);
        assertArrayEquals(single.toByteArray(), parallel.toByteArray());
        var a = encode(WIDTH, HEIGHT, options.toBuilder().threads(1).firstPassStats(single).build(), pictures);
        var b = encode(WIDTH, HEIGHT, options.toBuilder().threads(3).firstPassStats(single).build(), pictures);
        assertEquals(a.size(), b.size());
        for (var i = 0; i < a.size(); i++) {
            assertArrayEquals(a.get(i).data(), b.get(i).data(), "packet " + i);
        }
    }

    @Test
    @DisplayName("frames beyond the measured ones are coded as in a single pass")
    void beyondTheStatistics() {
        var pictures = varied(WIDTH, HEIGHT);
        var options = base(400_000).build();
        var stats = firstPass(WIDTH, HEIGHT, options, pictures.subList(0, 100));
        var packets = encode(WIDTH, HEIGHT, options.toBuilder().firstPassStats(stats).build(), pictures);
        assertEquals(pictures.size(), packets.size());
        var decoded = decode(WIDTH, HEIGHT, packets, encoderConfig(options, stats));
        assertEquals(pictures.size(), decoded.size());
        var rate = bits(packets) / (pictures.size() / (double) FPS);
        assertTrue(rate > 0.8 * 400_000 && rate < 1.2 * 400_000, "rate " + rate);
    }

    @Test
    @DisplayName("a new target bitrate in the second pass governs the bits of the frames not yet coded")
    void retarget() {
        var pictures = varied(WIDTH, HEIGHT);
        var options = base(300_000).build();
        var stats = firstPass(WIDTH, HEIGHT, options, pictures);
        var packets = new ArrayList<Packet>();
        var switchedBits = 0L;
        var switchedFrames = 0;
        try (var encoder = new H264Encoder(WIDTH, HEIGHT, options.toBuilder().firstPassStats(stats).build())) {
            for (var i = 0; i < pictures.size(); i++) {
                if (i == 100) {
                    switchedBits = bits(packets);
                    switchedFrames = packets.size();
                    encoder.setTargetBitrate(600_000);
                }
                encoder.encode(new VideoFrame(pictures.get(i), i, 0, false), packets::add);
            }
            encoder.flush(packets::add);
        }
        var expected = switchedBits + 600_000.0 * (pictures.size() - switchedFrames) / FPS;
        var actual = bits(packets);
        assertTrue(Math.abs(actual - expected) <= 0.03 * expected, actual + " bits, expected " + expected);
    }

    /** The configuration record of an encoder with the statistics. */
    static byte[] encoderConfig(H264EncoderOptions options, H264FirstPassStats stats) {
        try (var encoder = new H264Encoder(WIDTH, HEIGHT, options.toBuilder().firstPassStats(stats).build())) {
            return encoder.codecConfig().orElseThrow();
        }
    }

    static List<byte[]> decode(int width, int height, List<Packet> packets, byte[] avcC) {
        var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, FPS)).width(width)
                .height(height).codecConfig(avcC).build();
        var decoded = new ArrayList<byte[]>();
        try (var decoder = new H264Decoder(track)) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> decoded.add(i420(frame.picture())));
            }
            decoder.flush(frame -> decoded.add(i420(frame.picture())));
        }
        return decoded;
    }

    static byte[] i420(Picture picture) {
        var yuv = (YuvPicture) picture;
        var out = new byte[yuv.width() * yuv.height() * 3 / 2];
        var offset = 0;
        for (var row = 0; row < yuv.height(); row++) {
            System.arraycopy(yuv.y(), row * yuv.yStride(), out, offset, yuv.width());
            offset += yuv.width();
        }
        for (var plane : List.of(yuv.u(), yuv.v())) {
            for (var row = 0; row < yuv.chromaHeight(); row++) {
                System.arraycopy(plane, row * yuv.uvStride(), out, offset, yuv.chromaWidth());
                offset += yuv.chromaWidth();
            }
        }
        return out;
    }

    @Test
    @DisplayName("the second pass decodes in Smalt and in ffmpeg to exactly the encoder's reconstruction")
    void decodesToTheReconstruction() {
        var pictures = varied(WIDTH, HEIGHT).subList(30, 150);
        var options = base(350_000).build();
        var stats = firstPass(WIDTH, HEIGHT, options, pictures);
        var packets = new ArrayList<Packet>();
        var byDisplay = new TreeMap<Long, byte[]>();
        byte[] sps;
        byte[] pps;
        byte[] avcC;
        try (var encoder = new H264Encoder(WIDTH, HEIGHT, options.toBuilder().firstPassStats(stats).build())) {
            var core = encoder.core();
            core.setReconstructUnreferenced(true);
            for (var i = 0; i < pictures.size(); i++) {
                var packet = core.encode(pictures.get(i), i, 0, null);
                if (packet != null) {
                    packets.add(packet);
                    byDisplay.put(core.lastDisplayIndex(), core.lastReconstructionI420());
                }
            }
            for (var packet = core.flush(); packet != null; packet = core.flush()) {
                packets.add(packet);
                byDisplay.put(core.lastDisplayIndex(), core.lastReconstructionI420());
            }
            sps = core.sequenceParameterSet();
            pps = core.pictureParameterSet();
            avcC = core.configurationRecord();
        }
        var reconstructions = new ArrayList<>(byDisplay.values());
        var decoded = decode(WIDTH, HEIGHT, packets, avcC);
        assertEquals(pictures.size(), decoded.size());
        for (var i = 0; i < decoded.size(); i++) {
            assertArrayEquals(reconstructions.get(i), decoded.get(i), "picture " + i);
        }
        var encoded = new EncoderHarness.Encoded(H264Setup.of(WIDTH, HEIGHT, new Rational(FPS, 1), new Rational(1, FPS),
                H264Profile.MAIN, 0, H264RateControl.AVERAGE_BITRATE, 350_000, 0, 0, 23, 60, H264EncoderSpeed.BALANCED,
                ColorInfo.BT709_LIMITED, 1), packets, reconstructions, sps, pps, avcC, new long[0]);
        EncoderHarness.assertBitExact("two-pass", encoded);
    }

    @Nested
    @DisplayName("statistics")
    class Statistics {
        private final H264FirstPassStats stats = firstPass(64, 48, base(200_000).build(),
                EncoderHarness.sequence(64, 48, 40, 11, 2.0));

        @Test
        @DisplayName("round trip through their serialized form")
        void roundTrip() {
            var bytes = stats.toByteArray();
            var restored = H264FirstPassStats.fromByteArray(bytes);
            assertEquals(stats, restored);
            assertEquals(stats.hashCode(), restored.hashCode());
            assertArrayEquals(bytes, restored.toByteArray());
            assertEquals(64, restored.width());
            assertEquals(48, restored.height());
            assertEquals(40, restored.frameCount());
            assertEquals("H264FirstPassStats[64x48, frames=40]", restored.toString());
            assertEquals(java.time.Duration.ofNanos(Math.round(40 / 30.0 * 1e9)), restored.duration());
        }

        static Stream<Arguments> corruptions() {
            return Stream.of(
                    Arguments.of("truncated header", (UnaryOperator<byte[]>) b -> Arrays.copyOf(b, 12)),
                    Arguments.of("wrong magic", (UnaryOperator<byte[]>) b -> put(b, 0, 0x12345678)),
                    Arguments.of("another version", (UnaryOperator<byte[]>) b -> put(b, 4, 2)),
                    Arguments.of("zero width", (UnaryOperator<byte[]>) b -> put(b, 8, 0)),
                    Arguments.of("negative frame count", (UnaryOperator<byte[]>) b -> put(b, 16, -1)),
                    Arguments.of("one record missing", (UnaryOperator<byte[]>) b -> Arrays.copyOf(b, b.length - 24)),
                    Arguments.of("trailing byte", (UnaryOperator<byte[]>) b -> Arrays.copyOf(b, b.length + 1)),
                    Arguments.of("unknown picture type", (UnaryOperator<byte[]>) b -> put(b, 20, 3)),
                    Arguments.of("quantizer above 51", (UnaryOperator<byte[]>) b -> put(b, 24, 52)),
                    Arguments.of("quantizer below -6", (UnaryOperator<byte[]>) b -> put(b, 24, -7)),
                    Arguments.of("zero size", (UnaryOperator<byte[]>) b -> putLong(b, 28, 0)),
                    Arguments.of("zero duration", (UnaryOperator<byte[]>) b -> putDouble(b, 36, 0)),
                    Arguments.of("negative duration", (UnaryOperator<byte[]>) b -> putDouble(b, 36, -1)),
                    Arguments.of("infinite duration", (UnaryOperator<byte[]>) b -> putDouble(b, 36,
                            Double.POSITIVE_INFINITY)),
                    Arguments.of("NaN duration", (UnaryOperator<byte[]>) b -> putDouble(b, 36, Double.NaN)));
        }

        static byte[] put(byte[] data, int offset, int value) {
            var copy = data.clone();
            ByteBuffer.wrap(copy).putInt(offset, value);
            return copy;
        }

        static byte[] putLong(byte[] data, int offset, long value) {
            var copy = data.clone();
            ByteBuffer.wrap(copy).putLong(offset, value);
            return copy;
        }

        static byte[] putDouble(byte[] data, int offset, double value) {
            var copy = data.clone();
            ByteBuffer.wrap(copy).putDouble(offset, value);
            return copy;
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("corruptions")
        @DisplayName("reject corrupt serialized forms")
        void corrupt(String name, UnaryOperator<byte[]> corruption) {
            var corrupted = corruption.apply(stats.toByteArray());
            assertThrows(MediaException.Malformed.class, () -> H264FirstPassStats.fromByteArray(corrupted));
        }

        @Test
        @DisplayName("restore or reject, and never fail otherwise, under random byte changes")
        void randomCorruption() {
            var random = new Random(42);
            var bytes = stats.toByteArray();
            for (var round = 0; round < 2000; round++) {
                var corrupted = bytes.clone();
                for (var flips = 1 + random.nextInt(3); flips > 0; flips--) {
                    corrupted[random.nextInt(corrupted.length)] = (byte) random.nextInt(256);
                }
                try {
                    var restored = H264FirstPassStats.fromByteArray(corrupted);
                    assertArrayEquals(corrupted, restored.toByteArray());
                } catch (MediaException.Malformed expected) {
                    // rejected
                }
            }
        }

        @Test
        @DisplayName("are the same object on every finish")
        void finishTwice() {
            var pass = new H264FirstPass(64, 48, base(200_000).build());
            for (var i = 0; i < 5; i++) {
                pass.add(new VideoFrame(EncoderHarness.synthetic(64, 48, i, 1, 1.0), i, 0, false));
            }
            var first = pass.finish();
            assertSame(first, pass.finish());
            assertEquals(5, first.frameCount());
            assertThrows(IllegalStateException.class,
                    () -> pass.add(new VideoFrame(EncoderHarness.synthetic(64, 48, 5, 1, 1.0), 5, 0, false)));
        }

        @Test
        @DisplayName("convert other picture representations as the encoder does")
        void rgbInput() {
            var options = base(200_000).build();
            var yuv = EncoderHarness.sequence(64, 48, 12, 4, 2.0);
            var rgb = new ArrayList<Picture>();
            for (var picture : yuv) {
                rgb.add(ImageOps.toRgba(picture));
            }
            var fromRgb = firstPass(64, 48, options, rgb);
            assertEquals(12, fromRgb.frameCount());
            assertNotEquals(0, fromRgb.toByteArray().length);
        }
    }

    @Nested
    @DisplayName("statistics at the limits of their ranges")
    class Hostile {
        private static final int FRAMES = 24;

        /** Statistics of 64x48 pictures, IDR first and then P pictures unless the types say otherwise. */
        static H264FirstPassStats stats(byte[] types, int[] qps, long[] bits, double[] seconds) {
            return new H264FirstPassStats(64, 48, new PassRecords(types, qps, bits, seconds));
        }

        static Stream<Arguments> extremes() {
            var types = new byte[FRAMES];
            Arrays.fill(types, (byte) PassRecords.PREDICTED);
            types[0] = PassRecords.INTRA;
            var qps = new int[FRAMES];
            Arrays.fill(qps, 26);
            var bits = new long[FRAMES];
            Arrays.fill(bits, 4000);
            var alternating = new double[FRAMES];
            for (var i = 0; i < FRAMES; i++) {
                alternating[i] = i % 2 == 0 ? Double.MAX_VALUE : Double.MIN_VALUE;
            }
            var tiny = new double[FRAMES];
            Arrays.fill(tiny, Double.MIN_VALUE);
            var huge = new double[FRAMES];
            Arrays.fill(huge, Double.MAX_VALUE);
            var sizes = new long[FRAMES];
            var corners = new int[FRAMES];
            for (var i = 0; i < FRAMES; i++) {
                sizes[i] = i % 3 == 0 ? Long.MAX_VALUE : 1;
                corners[i] = i % 2 == 0 ? PassRecords.MIN_QP : PassRecords.MAX_QP;
            }
            var random = new Random(7);
            var randomTypes = new byte[FRAMES];
            var randomQps = new int[FRAMES];
            var randomBits = new long[FRAMES];
            var randomSeconds = new double[FRAMES];
            for (var i = 0; i < FRAMES; i++) {
                randomTypes[i] = (byte) random.nextInt(3);
                randomQps[i] = PassRecords.MIN_QP + random.nextInt(PassRecords.MAX_QP - PassRecords.MIN_QP + 1);
                randomBits[i] = 1 + (random.nextLong() >>> (1 + random.nextInt(63)));
                randomSeconds[i] = StrictMath.exp(-744 + 1453 * random.nextDouble());
            }
            var thirty = new double[FRAMES];
            Arrays.fill(thirty, 1 / 30.0);
            return Stream.of(
                    Arguments.of("durations at both ends of the range", stats(types, qps, bits, alternating)),
                    Arguments.of("the shortest durations", stats(types, qps, bits, tiny)),
                    Arguments.of("the longest durations", stats(types, qps, bits, huge)),
                    Arguments.of("the largest sizes and quantizers at both ends", stats(types, corners, sizes,
                            thirty)),
                    Arguments.of("random records over the whole ranges", stats(randomTypes, randomQps, randomBits,
                            randomSeconds)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("extremes")
        @DisplayName("keep every quantizer of the second pass inside the window and its stream decodable")
        void quantizerWindow(String name, H264FirstPassStats stats) {
            var options = base(200_000).quantizers(14, 45).firstPassStats(stats).build();
            var pictures = EncoderHarness.sequence(64, 48, FRAMES + 6, 31, 4.0);
            var qps = new ArrayList<Integer>();
            var packets = new ArrayList<Packet>();
            byte[] avcC;
            try (var encoder = new H264Encoder(64, 48, options)) {
                for (var i = 0; i < pictures.size(); i++) {
                    encoder.encode(new VideoFrame(pictures.get(i), i, 0, false), packet -> {
                        packets.add(packet);
                        qps.add(encoder.stats().lastQuantizer());
                    });
                }
                encoder.flush(packet -> {
                    packets.add(packet);
                    qps.add(encoder.stats().lastQuantizer());
                });
                avcC = encoder.codecConfig().orElseThrow();
            }
            assertEquals(pictures.size(), packets.size());
            assertTrue(qps.stream().allMatch(qp -> qp >= 14 && qp <= 45), name + ": quantizers " + qps);
            assertEquals(pictures.size(), decode(64, 48, packets, avcC).size());
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {
        private final H264FirstPassStats stats = firstPass(64, 48, base(200_000).build(),
                EncoderHarness.sequence(64, 48, 8, 11, 2.0));

        @Test
        @DisplayName("the first pass needs lossy average bitrate coding outside real-time mode")
        void firstPassModes() {
            assertThrows(IllegalArgumentException.class, () -> new H264FirstPass(64, 48,
                    base(200_000).rateControl(H264RateControl.CONSTANT_QUALITY).build()));
            assertThrows(IllegalArgumentException.class, () -> new H264FirstPass(64, 48,
                    base(200_000).rateControl(H264RateControl.CONSTANT_BITRATE).build()));
            assertThrows(IllegalArgumentException.class, () -> new H264FirstPass(64, 48,
                    base(200_000).realtime(true).build()));
            assertThrows(IllegalArgumentException.class, () -> new H264FirstPass(64, 48,
                    base(200_000).profile(H264Profile.HIGH_444_PREDICTIVE).lossless(true).build()));
            assertThrows(IllegalArgumentException.class, () -> new H264FirstPass(0, 48, base(200_000).build()));
        }

        @Test
        @DisplayName("the statistics need average bitrate coding outside real-time mode")
        void optionModes() {
            assertThrows(IllegalArgumentException.class, () -> base(200_000).firstPassStats(stats)
                    .rateControl(H264RateControl.CONSTANT_QUALITY).build());
            assertThrows(IllegalArgumentException.class, () -> base(200_000).firstPassStats(stats)
                    .rateControl(H264RateControl.CONSTANT_BITRATE).build());
            assertThrows(IllegalArgumentException.class, () -> base(200_000).firstPassStats(stats).realtime(true)
                    .build());
            var options = base(200_000).firstPassStats(stats).build();
            assertEquals(stats, options.firstPassStats().orElseThrow());
            assertEquals(options, options.toBuilder().build());
            assertTrue(options.toBuilder().noFirstPassStats().build().firstPassStats().isEmpty());
            assertNotEquals(options, options.toBuilder().noFirstPassStats().build());
        }

        @Test
        @DisplayName("the encoder rejects statistics of another picture size")
        void otherSize() {
            var options = base(200_000).firstPassStats(stats).build();
            assertThrows(IllegalArgumentException.class, () -> new H264Encoder(64, 64, options));
            try (var encoder = new H264Encoder(64, 48, options)) {
                assertEquals(options, encoder.options());
            }
        }

        @Test
        @DisplayName("the first pass rejects pictures of another size and timestamps that do not increase")
        void frames() {
            var pass = new H264FirstPass(64, 48, base(200_000).build());
            assertThrows(IllegalArgumentException.class,
                    () -> pass.add(new VideoFrame(EncoderHarness.synthetic(64, 64, 0, 1, 1.0), 0, 0, false)));
            pass.add(new VideoFrame(EncoderHarness.synthetic(64, 48, 0, 1, 1.0), 5, 0, false));
            assertThrows(IllegalArgumentException.class,
                    () -> pass.add(new VideoFrame(EncoderHarness.synthetic(64, 48, 1, 1, 1.0), 5, 0, false)));
        }
    }
}
