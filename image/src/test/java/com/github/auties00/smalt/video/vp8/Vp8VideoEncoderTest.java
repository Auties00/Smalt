package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the real-time VP8 encoder: every frame of every configuration must decode bit-exactly,
 * with the module's decoder and with ffmpeg, to the encoder's own reconstruction; key frames come
 * when asked; the stream does not depend on the threads; the rate follows bitrate steps; and
 * hostile parameters and content are handled. Pictures come from a synthetic panning scene, and
 * from the Xiph clips cached by earlier work when they are present.
 */
@DisplayName("real-time VP8 encoder")
class Vp8VideoEncoderTest {
    /**
     * The packets of a run, the MD5 of the encoder's reconstructions as packed I420, and the
     * reconstructions themselves when they were kept.
     */
    record Run(List<Packet> packets, String reconMd5, List<byte[]> recon, int width, int height) {
    }

    static Vp8Setup config(int width, int height, int bitrate) {
        return Vp8Setup.of(width, height, new Rational(30, 1), new Rational(1, 30), bitrate, 4, 63, 0, 6,
                false, 1, false, 1000, 500, 600, 1, 1);
    }

    static Vp8Setup with(Vp8Setup c, int speed, boolean errorResilient, int layers,
                                      int partitions, int threads) {
        return Vp8Setup.of(c.width(), c.height(), c.frameRate(), c.timeBase(), c.bitrate(), c.minQuantizer(),
                c.maxQuantizer(), c.keyframeInterval(), speed, errorResilient, layers, c.dropFrames(), c.bufferMillis(),
                c.initialBufferMillis(), c.optimalBufferMillis(), partitions, threads);
    }

    static VideoTrack track(int width, int height) {
        return VideoTrack.builder().id(1).codec(Codec.VP8).timeBase(new Rational(1, 30)).width(width).height(height)
                .build();
    }

    /**
     * Encodes pictures, checking that the module's decoder reproduces the reconstruction of every
     * coded frame.
     */
    static Run encode(Vp8Setup config, IntFunction<YuvPicture> frames, int count, boolean keepRecon,
                      IntPredicate forceKey, IntFunction<Vp8Setup> reconfigure) {
        var packets = new ArrayList<Packet>();
        var recons = new ArrayList<byte[]>();
        var digest = md5();
        var w = config.width();
        var h = config.height();
        var recon = YuvPicture.allocate(w, h, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        try (var encoder = config.encoder(); var decoder = new Vp8Decoder(track(w, h))) {
            encoder.engine().keepReconstruction(true);
            for (var i = 0; i < count; i++) {
                if (reconfigure != null) {
                    var next = reconfigure.apply(i);
                    if (next != null) {
                        encoder.reconfigure(next.options());
                    }
                }
                if (forceKey.test(i)) {
                    encoder.requestKeyframe();
                }
                var packet = encoder.encodeFrame(new VideoFrame(frames.apply(i), i, 1, false)).orElse(null);
                if (packet == null) {
                    continue;
                }
                packets.add(packet);
                encoder.engine().copyReconstruction(recon);
                var expected = Ivf.i420(recon);
                digest.update(expected);
                if (keepRecon) {
                    recons.add(expected);
                }
                var index = i;
                var decoded = new int[1];
                decoder.decode(packet, frame -> {
                    decoded[0]++;
                    assertArrayEquals(expected, Ivf.i420((YuvPicture) frame.picture()), "frame " + index);
                });
                assertEquals(1, decoded[0], "frame " + index + " was not shown");
            }
        }
        return new Run(packets, HexFormat.of().formatHex(digest.digest()), recons, w, h);
    }

    static Run encode(Vp8Setup config, List<YuvPicture> frames) {
        return encode(config, frames::get, frames.size(), false, i -> false, null);
    }

    static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Decodes a run with ffmpeg and checks every frame equals the encoder's reconstruction. */
    static void assertFfmpegMatches(Run run) throws IOException {
        if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
            return;
        }
        var ivf = Files.createTempFile("vp8rt", ".ivf");
        try {
            Files.write(ivf, Ivf.write(run.width(), run.height(), run.packets().stream().map(Packet::data).toList()));
            Oracle.assertDecodesCleanly(ivf);
            var output = new String(Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-i",
                    ivf.toString(), "-map", "0:v:0", "-f", "md5", "-pix_fmt", "yuv420p", "-")), StandardCharsets.US_ASCII);
            assertEquals("MD5=" + run.reconMd5(), output.strip(), "ffmpeg's decode differs");
        } finally {
            Files.deleteIfExists(ivf);
        }
    }

    /** Returns the frames of a cached clip, read lazily, or synthetic frames when it is absent. */
    static IntFunction<YuvPicture> clipOrSynthetic(String name, int width, int height, int frames) throws IOException {
        var clip = RtTestVideo.clip(name);
        if (clip != null && Files.size(clip) >= (long) frames * width * height * 3 / 2) {
            return i -> {
                try {
                    return RtTestVideo.read(clip, width, height, i, 1).getFirst();
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            };
        }
        return RtTestVideo.synthesize(width, height, frames)::get;
    }

    static boolean isKey(Packet packet) {
        return (packet.data()[0] & 1) == 0;
    }

    @Nested
    @DisplayName("bit-exactness")
    class BitExact {
        static Stream<Arguments> configurations() {
            return Stream.of(
                    Arguments.of("176x144 speed 6", 176, 144, 6, false, 1, 1, 1, 200_000),
                    Arguments.of("176x144 error resilient", 176, 144, 6, true, 1, 1, 1, 200_000),
                    Arguments.of("odd 33x17", 33, 17, 6, true, 1, 1, 1, 50_000),
                    Arguments.of("odd 75x49", 75, 49, 6, false, 1, 2, 1, 80_000),
                    Arguments.of("1x1", 1, 1, 6, false, 1, 1, 1, 10_000),
                    Arguments.of("speed 4", 160, 96, 4, false, 1, 1, 1, 150_000),
                    Arguments.of("speed 5", 160, 96, 5, false, 1, 1, 1, 150_000),
                    Arguments.of("speed 8", 160, 96, 8, false, 1, 1, 1, 150_000),
                    Arguments.of("speed 10", 160, 96, 10, false, 1, 1, 1, 150_000),
                    Arguments.of("speed 12", 160, 96, 12, false, 1, 1, 1, 150_000),
                    Arguments.of("speed 14 simple filter", 160, 96, 14, false, 1, 1, 1, 150_000),
                    Arguments.of("speed 16", 160, 96, 16, false, 1, 1, 1, 150_000),
                    Arguments.of("two temporal layers", 176, 144, 6, true, 2, 1, 1, 200_000),
                    Arguments.of("three temporal layers", 176, 144, 6, true, 3, 1, 1, 200_000),
                    Arguments.of("eight token partitions", 176, 144, 6, false, 1, 8, 1, 200_000),
                    Arguments.of("four threads", 320, 240, 6, false, 1, 4, 4, 400_000),
                    Arguments.of("high bitrate", 176, 144, 6, false, 1, 1, 1, 5_000_000),
                    Arguments.of("low bitrate", 176, 144, 6, false, 1, 1, 1, 20_000));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("configurations")
        @DisplayName("every frame decodes to the reconstruction with the module's decoder and ffmpeg")
        void decodes(String name, int width, int height, int speed, boolean errorResilient, int layers, int partitions,
                     int threads, int bitrate) throws IOException {
            var config = with(config(width, height, bitrate), speed, errorResilient, layers, partitions, threads);
            var run = encode(config, RtTestVideo.synthesize(width, height, 45));
            assertEquals(45, run.packets().size());
            assertTrue(isKey(run.packets().getFirst()));
            assertFfmpegMatches(run);
        }

        @Test
        @DisplayName("a natural clip at 640x480 decodes to the reconstruction, error resilient or not")
        void naturalClip() throws IOException {
            var frames = clipOrSynthetic("johnny480.yuv", 640, 480, 60);
            for (var errorResilient : new boolean[]{false, true}) {
                var run = encode(with(config(640, 480, 600_000), 6, errorResilient, 1, 1, 1), frames, 60, true,
                        i -> false, null);
                assertFfmpegMatches(run);
                var psnr = 0.0;
                for (var i = 0; i < 60; i++) {
                    psnr += Quality.psnr(Ivf.i420(frames.apply(i)), run.recon().get(i));
                }
                assertTrue(psnr / 60 > 30, "psnr " + psnr / 60);
            }
        }
    }

    @Nested
    @DisplayName("key frames")
    class KeyFrames {
        @Test
        @DisplayName("forced and requested key frames come mid-stream and decode on their own")
        void forced() throws IOException {
            var frames = RtTestVideo.synthesize(160, 96, 40);
            var run = encode(config(160, 96, 150_000), frames::get, frames.size(), true, i -> i == 13 || i == 29, null);
            for (var i = 0; i < run.packets().size(); i++) {
                var expected = i == 0 || i == 13 || i == 29;
                assertEquals(expected, isKey(run.packets().get(i)), "frame " + i);
                assertEquals(expected, run.packets().get(i).keyframe(), "frame " + i);
            }
            assertFfmpegMatches(run);
            try (var decoder = new Vp8Decoder(track(160, 96))) {
                for (var i = 29; i < run.packets().size(); i++) {
                    var index = i;
                    decoder.decode(run.packets().get(i), frame ->
                            assertArrayEquals(run.recon().get(index), Ivf.i420((YuvPicture) frame.picture())));
                }
            }
        }

        @Test
        @DisplayName("requestKeyframe makes the next frame a key frame")
        void requested() {
            var frames = RtTestVideo.synthesize(64, 64, 12);
            try (var encoder = config(64, 64, 100_000).encoder()) {
                for (var i = 0; i < frames.size(); i++) {
                    if (i == 7) {
                        encoder.requestKeyframe();
                    }
                    var packet = encoder.encodeFrame(new VideoFrame(frames.get(i), i, 1, false)).orElse(null);
                    assertEquals(i == 0 || i == 7, packet.keyframe(), "frame " + i);
                }
                assertEquals(2, encoder.stats().keyframesEncoded());
            }
        }

        @Test
        @DisplayName("periodic key frames follow the key frame interval")
        void periodic() throws IOException {
            var c = config(96, 64, 120_000);
            c = Vp8Setup.of(96, 64, c.frameRate(), c.timeBase(), c.bitrate(), 4, 63, 10, 6, false, 1, false,
                    1000, 500, 600, 1, 1);
            var run = encode(c, RtTestVideo.synthesize(96, 64, 35));
            for (var i = 0; i < run.packets().size(); i++) {
                assertEquals(i % 10 == 0, isKey(run.packets().get(i)), "frame " + i);
            }
            assertFfmpegMatches(run);
        }
    }

    @Nested
    @DisplayName("color")
    class Color {
        // Flat 32x32 tiles of saturated colors: inside a tile, away from the chroma subsampling at its edges,
        // reading BT.709 or full-range samples as BT.601 limited range moves the colors by several levels on
        // average, while the codec's own loss at a fine quantizer stays under one level.
        static RgbaPicture tiles() {
            int[][] colors = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 0}, {0, 255, 255}, {255, 0, 255},
                    {40, 40, 40}, {220, 220, 220}};
            var picture = RgbaPicture.allocate(128, 64, false);
            var pixels = picture.pixels();
            for (var y = 0; y < 64; y++) {
                for (var x = 0; x < 128; x++) {
                    var color = colors[(y / 32) * 4 + x / 32];
                    var at = y * picture.stride() + x * 4;
                    pixels[at] = (byte) color[0];
                    pixels[at + 1] = (byte) color[1];
                    pixels[at + 2] = (byte) color[2];
                    pixels[at + 3] = (byte) 255;
                }
            }
            return picture;
        }

        static double tileInteriorError(RgbaPicture expected, RgbaPicture actual) {
            long sum = 0;
            long count = 0;
            for (var y = 0; y < expected.height(); y++) {
                for (var x = 0; x < expected.width(); x++) {
                    if (x % 32 < 4 || x % 32 >= 28 || y % 32 < 4 || y % 32 >= 28) {
                        continue;
                    }
                    for (var channel = 0; channel < 3; channel++) {
                        sum += Math.abs((expected.pixels()[y * expected.stride() + x * 4 + channel] & 0xFF)
                                - (actual.pixels()[y * actual.stride() + x * 4 + channel] & 0xFF));
                        count++;
                    }
                }
            }
            return (double) sum / count;
        }

        static Vp8EncoderOptions fine() {
            return Vp8EncoderOptions.builder().bitrate(4_000_000).quantizers(0, 4).build();
        }

        static Stream<Arguments> colors() {
            return Stream.of(Arguments.of("BT.709 limited", ColorInfo.BT709_LIMITED),
                    Arguments.of("BT.601 full", ColorInfo.JPEG));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("colors")
        @DisplayName("4:2:0 pictures in another matrix or range are converted to BT.601 limited range")
        void converted(String name, ColorInfo color) {
            var source = tiles();
            var input = ImageOps.toYuv(source, ChromaFormat.YUV420, color);
            try (var encoder = new Vp8Encoder(128, 64, fine()); var decoder = new Vp8Decoder(track(128, 64))) {
                var packet = encoder.encodeFrame(new VideoFrame(input, 0, 1, false)).orElseThrow();
                var decoded = new RgbaPicture[1];
                decoder.decode(packet, frame -> decoded[0] = ImageOps.toRgba(frame.picture()));
                var error = tileInteriorError(source, decoded[0]);
                assertTrue(error < 1, name + " input decodes " + error + " levels away from the source");
            }
        }

        @Test
        @DisplayName("BT.601 limited-range 4:2:0 pictures, also with an unspecified matrix, are coded as given")
        void passedThrough() {
            var input = ImageOps.toYuv(tiles(), ChromaFormat.YUV420, ColorInfo.BT601_LIMITED);
            var unspecified = YuvPicture.allocate(128, 64, ChromaFormat.YUV420, false,
                    new ColorInfo(MatrixCoefficients.UNSPECIFIED, ColorRange.LIMITED, ColorPrimaries.UNSPECIFIED,
                            TransferCharacteristics.UNSPECIFIED));
            for (var plane = 0; plane < 3; plane++) {
                var from = plane == 0 ? input.y() : plane == 1 ? input.u() : input.v();
                var to = plane == 0 ? unspecified.y() : plane == 1 ? unspecified.u() : unspecified.v();
                System.arraycopy(from, 0, to, 0, Math.min(from.length, to.length));
            }
            byte[] expected;
            try (var encoder = new Vp8Encoder(128, 64, fine())) {
                expected = encoder.encodeFrame(new VideoFrame(input, 0, 1, false)).orElseThrow().data();
            }
            try (var encoder = new Vp8Encoder(128, 64, fine())) {
                assertArrayEquals(expected, encoder.encodeFrame(new VideoFrame(unspecified, 0, 1, false)).orElseThrow()
                        .data());
            }
        }

        @Test
        @DisplayName("a stream of converted pictures codes as the same pictures converted beforehand")
        void convertedStream() {
            var bt709 = RtTestVideo.synthesize(128, 64, 6).stream()
                    .map(frame -> ImageOps.toYuv(frame, ChromaFormat.YUV420, ColorInfo.BT709_LIMITED)).toList();
            var options = config(128, 64, 300_000).options();
            try (var converting = new Vp8Encoder(128, 64, options); var direct = new Vp8Encoder(128, 64, options)) {
                for (var i = 0; i < bt709.size(); i++) {
                    var converted = ImageOps.toYuv(bt709.get(i), ChromaFormat.YUV420, ColorInfo.BT601_LIMITED);
                    var expected = direct.encodeFrame(new VideoFrame(converted, i, 1, false)).orElseThrow().data();
                    var actual = converting.encodeFrame(new VideoFrame(bt709.get(i), i, 1, false)).orElseThrow().data();
                    assertArrayEquals(expected, actual, "frame " + i);
                }
            }
        }
    }

    @Nested
    @DisplayName("threads")
    class Threads {
        @Test
        @DisplayName("the stream is the same for one and several threads")
        void deterministic() {
            var frames = RtTestVideo.synthesize(320, 240, 30);
            var single = encode(config(320, 240, 500_000), frames);
            for (var threads : new int[]{2, 3, 8}) {
                var multi = encode(with(config(320, 240, 500_000), 6, false, 1, 1, threads), frames);
                assertEquals(single.packets().size(), multi.packets().size());
                for (var i = 0; i < single.packets().size(); i++) {
                    assertArrayEquals(single.packets().get(i).data(), multi.packets().get(i).data(),
                            threads + " threads, frame " + i);
                }
            }
        }
    }

    @Nested
    @DisplayName("rate control")
    class RateControl {
        static final int[] CALL_STEPS = {350_000, 1_020_000, 128_000, 600_000};

        static final int SEGMENT = 75;

        /** Encodes a clip whose bitrate steps every {@link #SEGMENT} frames, returning each frame's bits (0 if dropped). */
        static long[] encodeSteps(String clip, int width, int height, int[] steps, boolean dropFrames) throws IOException {
            var count = SEGMENT * steps.length;
            var frames = clipOrSynthetic(clip, width, height, count);
            var c = config(width, height, steps[0]);
            var base = Vp8Setup.of(width, height, c.frameRate(), c.timeBase(), steps[0], 4, 63, 0, 6, false,
                    1, dropFrames, 1000, 500, 600, 1, 1);
            var run = encode(base, frames, count, false, i -> false,
                    i -> i % SEGMENT == 0 && i > 0 ? base.withBitrate(steps[i / SEGMENT]) : null);
            if (!dropFrames) {
                assertEquals(count, run.packets().size(), "no frame may be dropped");
            }
            assertFfmpegMatches(run);
            var sizes = new long[count];
            for (var packet : run.packets()) {
                sizes[(int) packet.pts()] = packet.size() * 8L;
            }
            return sizes;
        }

        /**
         * Returns the bits of the one-second windows, every half second, after the first second of
         * each step; the stream's own start-up is given half a second more, because the rate model
         * converges from its default correction factor there (libvpx misses its target by 15
         * percent in the second second of the 1280x720 clip at 600 kbit/s, this encoder by 10).
         */
        static List<long[]> windows(long[] sizes, int[] steps) {
            var windows = new ArrayList<long[]>();
            for (var s = 0; s < steps.length; s++) {
                for (var start = s * SEGMENT + (s == 0 ? 45 : 30); start + 30 <= (s + 1) * SEGMENT; start += 15) {
                    var bits = 0L;
                    var dropped = 0L;
                    for (var i = start; i < start + 30; i++) {
                        bits += sizes[i];
                        dropped += sizes[i] == 0 ? 1 : 0;
                    }
                    windows.add(new long[]{s, start, bits, dropped});
                }
            }
            return windows;
        }

        static String describe(List<long[]> windows, int[] steps) {
            var report = new StringBuilder();
            for (var w : windows) {
                var target = steps[(int) w[0]];
                report.append(String.format("%d-%d: %d bps (target %d, %+.1f%%, %d dropped)%n", w[1], w[1] + 30, w[2], target,
                        100.0 * (w[2] - target) / target, w[3]));
            }
            return report.toString();
        }

        static Stream<Arguments> reachable() {
            return Stream.of(Arguments.of("johnny480.yuv", 640, 480, CALL_STEPS),
                    Arguments.of("johnny720.yuv", 1280, 720, new int[]{600_000, 1_500_000, 700_000, 1_200_000}));
        }

        @ParameterizedTest(name = "{1}x{2}")
        @MethodSource("reachable")
        @DisplayName("each second after the first of a reachable bitrate step is within 5 percent of the target")
        void steps(String clip, int width, int height, int[] steps) throws IOException {
            var windows = windows(encodeSteps(clip, width, height, steps, false), steps);
            var report = describe(windows, steps);
            System.out.print(width + "x" + height + System.lineSeparator() + report);
            for (var w : windows) {
                var target = steps[(int) w[0]];
                assertTrue(Math.abs(w[2] - target) <= 0.05 * target, report);
            }
        }

        /**
         * At 1280x720 the clip needs about 450 kbit/s at the largest quantizer, with libvpx as with
         * this encoder, so the 350 and 128 kbit/s call steps are reachable only by dropping frames.
         * The steps that follow them start from a drained buffer, which the rate control refills by
         * spending less than the target, as libvpx does.
         */
        @Test
        @DisplayName("unreachable call steps at 1280x720 drop frames to meet the target")
        void unreachable() throws IOException {
            var sizes = encodeSteps("johnny720.yuv", 1280, 720, CALL_STEPS, true);
            var windows = windows(sizes, CALL_STEPS);
            var report = describe(windows, CALL_STEPS);
            System.out.print("1280x720 with frame dropping" + System.lineSeparator() + report);
            for (var s : new int[]{0, 2}) {
                var bits = 0L;
                for (var i = s * SEGMENT + 30; i < (s + 1) * SEGMENT; i++) {
                    bits += sizes[i];
                }
                var rate = bits * 30.0 / (SEGMENT - 30);
                assertTrue(Math.abs(rate - CALL_STEPS[s]) <= 0.1 * CALL_STEPS[s], rate + System.lineSeparator() + report);
            }
            for (var s : new int[]{1, 3}) {
                for (var i = s * SEGMENT + 30; i < (s + 1) * SEGMENT; i++) {
                    assertTrue(sizes[i] > 0, "frame " + i + " dropped at a reachable bitrate");
                }
            }
        }

        @Test
        @DisplayName("an unreachable bitrate drops frames only when dropping is enabled")
        void drops() throws IOException {
            var frames = RtTestVideo.synthesize(320, 240, 60);
            var c = config(320, 240, 8_000);
            var run = encode(c, frames);
            assertEquals(60, run.packets().size());
            var dropping = Vp8Setup.of(320, 240, c.frameRate(), c.timeBase(), 8_000, 4, 63, 0, 6, true, 1,
                    true, 1000, 500, 600, 1, 1);
            var dropped = encode(dropping, frames);
            assertTrue(dropped.packets().size() < 60, "no frame dropped");
            assertFfmpegMatches(dropped);
        }

        static Stream<Arguments> settled() {
            return Stream.of(Arguments.of("johnny480.yuv", 640, 480, 300_000, 900_000),
                    Arguments.of("johnny720.yuv", 1280, 720, 600_000, 1_500_000));
        }

        // The raise comes after 240 frames, when the quantizer history (150 inter frames) bounds the
        // quantizers; with the history of the old bitrate the first second fell 12 to 14 percent short.
        @ParameterizedTest(name = "{1}x{2}")
        @MethodSource("settled")
        @DisplayName("the first second after a raise of a settled stream is within 8 percent of the new target")
        void settledRaise(String clip, int width, int height, int from, int to) throws IOException {
            var raise = 240;
            var count = raise + 30;
            var c = config(width, height, from);
            var run = encode(c, clipOrSynthetic(clip, width, height, count), count, false, i -> false,
                    i -> i == raise ? c.withBitrate(to) : null);
            var bits = run.packets().stream().filter(p -> p.pts() >= raise).mapToLong(p -> p.size() * 8L).sum();
            assertTrue(Math.abs(bits - to) <= 0.08 * to, bits + " bits in the second after the raise to " + to);
        }

        // Two seconds of a still picture let the buffer fill above its optimal level, so the first
        // moving frames get targets above the per-frame bandwidth, by at most half the overshoot limit.
        @Test
        @DisplayName("a smaller overshoot limit makes the first moving frames after a still scene smaller")
        void overshoot() {
            var moving = RtTestVideo.synthesize(160, 96, 30);
            IntFunction<YuvPicture> frames = i -> i < 60 ? moving.getFirst() : moving.get(i - 60);
            var c = config(160, 96, 200_000);
            var loose = encode(c, frames, 90, false, i -> false, null);
            var tight = encode(c.withRateTolerance(100, 15), frames, 90, false, i -> false, null);
            var looseBits = loose.packets().stream().skip(60).limit(10).mapToLong(p -> p.size() * 8L).sum();
            var tightBits = tight.packets().stream().skip(60).limit(10).mapToLong(p -> p.size() * 8L).sum();
            assertTrue(tightBits < looseBits, tightBits + " >= " + looseBits);
            assertEquals(encode(c, frames, 90, false, i -> false, null).reconMd5(),
                    encode(c.withRateTolerance(100, 100), frames, 90, false, i -> false, null).reconMd5(),
                    "libvpx's defaults are the defaults");
        }

        @Test
        @DisplayName("key frames after the first keep the quantizer of the frames before them")
        void ambientKeyFrames() throws IOException {
            var c = Vp8Setup.of(640, 480, new Rational(30, 1), new Rational(1, 30), 500_000, 4, 63, 60, 6,
                    true, 1, true, 1000, 500, 600, 1, 1);
            var frames = clipOrSynthetic("johnny480.yuv", 640, 480, 181);
            try (var encoder = c.encoder()) {
                var recent = new ArrayDeque<Integer>();
                for (var i = 0; i <= 180; i++) {
                    var packet = encoder.encodeFrame(new VideoFrame(frames.apply(i), i, 1, false)).orElse(null);
                    if (packet == null) {
                        continue;
                    }
                    var q = encoder.stats().lastQuantizerIndex();
                    if (packet.keyframe() && i > 0) {
                        var average = recent.stream().mapToInt(Integer::intValue).average().orElseThrow();
                        assertTrue(q <= average + 2, "key frame " + i + " at q" + q + " after inter frames at q" + average);
                    } else if (!packet.keyframe()) {
                        recent.addLast(q);
                        if (recent.size() > 10) {
                            recent.removeFirst();
                        }
                    }
                }
                assertEquals(4, encoder.stats().keyframesEncoded());
            }
        }

        @Test
        @DisplayName("a key frame limit makes requested key frames smaller")
        void keyframeLimit() throws IOException {
            var c = config(640, 480, 400_000);
            var frames = clipOrSynthetic("kriste480.yuv", 640, 480, 120);
            IntPredicate force = i -> i == 60 || i == 90;
            var unlimited = encode(c, frames, 120, false, force, null);
            var limited = encode(c.withMaxKeyframeBitratePercent(150), frames, 120, false, force, null);
            for (var i : new int[]{60, 90}) {
                assertTrue(isKey(limited.packets().get(i)));
                assertTrue(limited.packets().get(i).size() < unlimited.packets().get(i).size(),
                        "key frame " + i + ": " + limited.packets().get(i).size() + " >= "
                                + unlimited.packets().get(i).size());
            }
        }
    }

    @Nested
    @DisplayName("hostile input")
    class Hostile {
        @Test
        @DisplayName("the frame buffers are allocated under the options' limits")
        void frameBuffersHonorLimits() {
            // 320x240 passes the picture checks, but a bordered 384x304 luma plane does not fit 100000 bytes
            var limits = MediaLimits.builder().maxBytes(100_000).build();
            var options = Vp8EncoderOptions.defaults().toBuilder().limits(limits).build();
            assertThrows(MediaException.LimitExceeded.class, () -> new Vp8Encoder(320, 240, options));
        }

        @Test
        @DisplayName("invalid configurations are rejected")
        void invalidConfigurations() {
            var r = new Rational(30, 1);
            var t = new Rational(1, 30);
            assertThrows(IllegalArgumentException.class, () -> Vp8Setup.of(0, 10, r, t, 1000, 4, 63, 0, 6, false, 1, false, 1000, 500, 600, 1, 1).encoder());
            assertThrows(IllegalArgumentException.class, () -> Vp8Setup.of(16384, 10, r, t, 1000, 4, 63, 0, 6, false, 1, false, 1000, 500, 600, 1, 1).encoder());
            assertThrows(MediaException.LimitExceeded.class, () -> new Vp8Encoder(640, 480, Vp8EncoderOptions.defaults()
                    .toBuilder().limits(MediaLimits.builder().maxDimension(320).build()).build()));
            assertThrows(NullPointerException.class, () -> new Vp8Encoder(16, 16, null));
            assertThrows(IllegalArgumentException.class, () -> Vp8Setup.of(10, 10, r, t, 0, 4, 63, 0, 6, false, 1, false, 1000, 500, 600, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> Vp8Setup.of(10, 10, r, t, 1000, 40, 30, 0, 6, false, 1, false, 1000, 500, 600, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> Vp8Setup.of(10, 10, r, t, 1000, 4, 64, 0, 6, false, 1, false, 1000, 500, 600, 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> Vp8EncoderOptions.builder().keyframeInterval(0).build());
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderSpeed.of(3));
            assertThrows(IllegalArgumentException.class, () -> Vp8EncoderSpeed.of(17));
            assertThrows(IllegalArgumentException.class, () -> Vp8Setup.of(10, 10, r, t, 1000, 4, 63, 0, 6, false, 4, false, 1000, 500, 600, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> Vp8Setup.of(10, 10, r, t, 1000, 4, 63, 0, 6, false, 1, false, 0, 0, 1, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> Vp8Setup.of(10, 10, r, t, 1000, 4, 63, 0, 6, false, 1, false, 1000, 2000, 600, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> Vp8Setup.of(10, 10, r, t, 1000, 4, 63, 0, 6, false, 1, false, 1000, 500, 600, 3, 1));
            assertThrows(IllegalArgumentException.class, () -> Vp8Setup.of(10, 10, r, t, 1000, 4, 63, 0, 6, false, 1, false, 1000, 500, 600, 1, -1));
            assertThrows(IllegalArgumentException.class, () -> Vp8Setup.of(10, 10, new Rational(0, 1), t, 1000, 4, 63, 0, 6, false, 1, false, 1000, 500, 600, 1, 1));
            assertThrows(NullPointerException.class, () -> Vp8Setup.of(10, 10, null, t, 1000, 4, 63, 0, 6, false, 1, false, 1000, 500, 600, 1, 1));
            var valid = Vp8Setup.of(10, 10, r, t, 1000, 4, 63, 0, 6, false, 1, false, 1000, 500, 600, 1, 1);
            assertThrows(IllegalArgumentException.class, () -> valid.withRateTolerance(-1, 15));
            assertThrows(IllegalArgumentException.class, () -> valid.withRateTolerance(101, 15));
            assertThrows(IllegalArgumentException.class, () -> valid.withRateTolerance(100, 1001));
            assertThrows(IllegalArgumentException.class, () -> valid.withMaxKeyframeBitratePercent(99));
            assertThrows(IllegalArgumentException.class, () -> valid.options().toBuilder()
                    .encodeBreakout((1 << 24) + 1).build());
            assertThrows(IllegalArgumentException.class, () -> valid.options().toBuilder().bitrate(1L << 31).build());
            assertThrows(IllegalArgumentException.class, () -> valid.options().toBuilder()
                    .bufferSize(Duration.ofMillis(1000))
                    .initialBufferLevel(Duration.ofMillis(500)).optimalBufferLevel(Duration.ZERO).build());
            assertThrows(NullPointerException.class, () -> valid.options().toBuilder().speed(null).build());
        }

        @Test
        @DisplayName("the real-time preset uses WebRTC's rate tolerances and key frame limit")
        void realtimePreset() {
            var at30 = Vp8Setup.realtime(320, 240, new Rational(30, 1), 300_000);
            assertEquals(100, at30.undershootPercent());
            assertEquals(15, at30.overshootPercent());
            assertEquals(OptionalInt.of(900), at30.maxKeyframeBitratePercent());
            assertEquals(OptionalInt.of(450),
                    Vp8Setup.realtime(320, 240, new Rational(15, 1), 300_000).maxKeyframeBitratePercent());
            var plain = Vp8Setup.of(10, 10, new Rational(30, 1), new Rational(1, 30), 1000, 4, 63, 0, 6, false,
                    1, false, 1000, 500, 600, 1, 1);
            assertEquals(100, plain.undershootPercent());
            assertEquals(100, plain.overshootPercent());
            assertTrue(plain.maxKeyframeBitratePercent().isEmpty());
            assertEquals(at30.options(), Vp8EncoderOptions.forRealtime().toBuilder().bitrate(300_000).build());
        }

        @Test
        @DisplayName("wrong sizes, decreasing timestamps, illegal reconfigurations and closed encoders are rejected")
        void misuse() {
            var frame = RtTestVideo.synthesize(64, 48, 1).getFirst();
            var encoder = config(64, 48, 100_000).encoder();
            assertThrows(IllegalArgumentException.class, () -> encoder.encodeFrame(
                    new VideoFrame(RtTestVideo.synthesize(64, 32, 1).getFirst(), 0, 1, false)).orElse(null));
            encoder.encodeFrame(new VideoFrame(frame, 5, 1, false)).orElse(null);
            assertThrows(IllegalArgumentException.class, () -> encoder.encodeFrame(new VideoFrame(frame, 5, 1, false))
                    .orElse(null));
            assertThrows(IllegalArgumentException.class, () -> encoder.encodeFrame(new VideoFrame(frame, 4, 1, false))
                    .orElse(null));
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.reconfigure(encoder.options().toBuilder().timeBase(new Rational(1, 90_000)).build()));
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.reconfigure(with(config(64, 48, 100_000), 6, true, 1, 1, 1).options()));
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.reconfigure(encoder.options().toBuilder().limits(MediaLimits.builder()
                            .maxPixels(1 << 20).build()).build()));
            assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(0));
            assertThrows(NullPointerException.class, () -> encoder.encodeFrame(null).orElse(null));
            encoder.close();
            assertThrows(IllegalStateException.class, () -> encoder.encodeFrame(new VideoFrame(frame, 9, 1, false))
                    .orElse(null));
        }

        @Test
        @DisplayName("missing, jittering and jumping timestamps still code every frame")
        void timestamps() {
            var frames = RtTestVideo.synthesize(96, 64, 30);
            var random = new Random(7);
            try (var encoder = Vp8Setup.realtime(96, 64, new Rational(30, 1), 100_000).encoder()) {
                var pts = 0L;
                var count = 0;
                for (var i = 0; i < frames.size(); i++) {
                    long stamp;
                    if (i % 7 == 3) {
                        stamp = Packet.NO_TIMESTAMP;
                        pts += 3000;
                    } else {
                        pts += 3000 + random.nextInt(-1500, 1500) + (i == 20 ? 900_000 : 0);
                        stamp = pts;
                    }
                    if (encoder.encodeFrame(new VideoFrame(frames.get(i), stamp, 0, false)).orElse(null) != null) {
                        count++;
                    }
                }
                assertTrue(count >= 28, "coded " + count);
            }
        }

        static Stream<Arguments> contents() {
            return Stream.of(Arguments.of("black", 0), Arguments.of("white", 1), Arguments.of("noise", 2),
                    Arguments.of("checkerboard", 3), Arguments.of("flashing", 4));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("contents")
        @DisplayName("extreme content at extreme bitrates decodes to the reconstruction")
        void extremeContent(String name, int kind) throws IOException {
            var random = new Random(kind);
            var frames = new ArrayList<YuvPicture>();
            for (var i = 0; i < 20; i++) {
                var p = YuvPicture.allocate(80, 48, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
                for (var plane : new byte[][]{p.y(), p.u(), p.v()}) {
                    for (var j = 0; j < plane.length; j++) {
                        plane[j] = switch (kind) {
                            case 0 -> 0;
                            case 1 -> (byte) 255;
                            case 2 -> (byte) random.nextInt(256);
                            case 3 -> (byte) (((j ^ (j >> 4)) & 1) * 255);
                            default -> (byte) (i % 2 == 0 ? 0 : 255);
                        };
                    }
                }
                frames.add(p);
            }
            for (var bitrate : new int[]{1, 30_000_000}) {
                var run = encode(config(80, 48, bitrate), frames);
                assertFfmpegMatches(run);
            }
        }

        @Test
        @DisplayName("RGBA and 4:4:4 pictures are converted")
        void otherPictures() {
            var rgba = new byte[64 * 32 * 4];
            for (var i = 0; i < rgba.length; i++) {
                rgba[i] = (byte) (i * 7);
            }
            var yuv444 = YuvPicture.allocate(64, 32, ChromaFormat.YUV444, false, ColorInfo.BT601_LIMITED);
            Arrays.fill(yuv444.y(), (byte) 90);
            try (var encoder = config(64, 32, 50_000).encoder()) {
                var output = new ArrayList<Packet>();
                encoder.encode(new VideoFrame(RgbaPicture.of(64, 32, 64 * 4, rgba, false), 0, 1, false), output::add);
                encoder.encode(new VideoFrame(yuv444, 1, 1, false), output::add);
                encoder.flush(output::add);
                assertEquals(2, output.size());
                assertTrue(encoder.codecConfig().isEmpty());
                assertEquals(Codec.VP8, encoder.codec());
            }
        }

        @Test
        @DisplayName("the widest and tallest pictures encode")
        void extremeSizes() {
            for (var size : new int[][]{{16383, 2}, {2, 16383}}) {
                var picture = YuvPicture.allocate(size[0], size[1], ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
                Arrays.fill(picture.y(), (byte) 77);
                try (var encoder = config(size[0], size[1], 100_000).encoder()) {
                    var first = encoder.encodeFrame(new VideoFrame(picture, 0, 1, false)).orElse(null);
                    var second = encoder.encodeFrame(new VideoFrame(picture, 1, 1, false)).orElse(null);
                    assertTrue(first.keyframe());
                    assertFalse(second.keyframe());
                }
            }
        }

        @Test
        @DisplayName("bitrate, quantizer and speed changes on a running stream keep it decodable")
        void reconfigurations() throws IOException {
            var frames = RtTestVideo.synthesize(160, 96, 60);
            var base = config(160, 96, 200_000);
            var random = new Random(3);
            var run = encode(base, frames::get, frames.size(), false, i -> i == 33, i -> {
                if (i % 6 != 5) {
                    return null;
                }
                var min = random.nextInt(0, 30);
                return Vp8Setup.of(160, 96, new Rational(random.nextInt(5, 60), 1), base.timeBase(),
                        random.nextInt(1, 3_000_000), min, random.nextInt(min, 64), random.nextInt(0, 20),
                        random.nextInt(4, 17), false, 1, random.nextBoolean(), random.nextInt(500, 3000), 250, 400, 1, 1);
            });
            assertFfmpegMatches(run);
        }

        @Test
        @DisplayName("encoding finishes in bounded time on hostile streams of settings")
        void bounded() {
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                var frames = RtTestVideo.synthesize(48, 32, 10);
                for (var speed = 4; speed <= 16; speed++) {
                    for (var layers = 1; layers <= 3; layers++) {
                        var c = with(config(48, 32, 1 << speed), speed, layers > 1, layers, 1 << (speed & 3), 2);
                        var run = encode(c, frames);
                        assertTrue(run.packets().size() > 0);
                    }
                }
            });
        }

        @Test
        @DisplayName("a dropped frame yields no packet and counts as dropped")
        void droppedFrame() {
            var frames = RtTestVideo.synthesize(320, 240, 30);
            var c = Vp8Setup.of(320, 240, new Rational(30, 1), new Rational(1, 30), 2_000, 4, 63, 0, 6, true,
                    1, true, 1000, 0, 600, 1, 1);
            try (var encoder = c.encoder()) {
                var dropped = 0;
                for (var i = 0; i < frames.size(); i++) {
                    var output = new ArrayList<Packet>();
                    encoder.encode(new VideoFrame(frames.get(i), i, 1, false), output::add);
                    if (output.isEmpty()) {
                        dropped++;
                    }
                }
                assertTrue(dropped > 0);
                assertEquals(dropped, encoder.stats().framesDropped());
            }
        }
    }
}
