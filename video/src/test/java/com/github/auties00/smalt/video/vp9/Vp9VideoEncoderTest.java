package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.MediaException;
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
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the real-time VP9 encoder through its public API: every frame of every configuration
 * must decode bit-exactly, with the module's decoder and with ffmpeg's two VP9 decoders, to the
 * encoder's own reconstruction; key frames come when asked; the stream does not depend on the
 * threads; the rate follows bitrate steps; and hostile parameters and content are handled.
 * Pictures come from a synthetic panning scene, and from the Xiph clips cached by earlier work
 * when they are present.
 */
@DisplayName("real-time VP9 encoder")
class Vp9VideoEncoderTest {
    /**
     * The packets of a run, the MD5 of the encoder's reconstructions as packed I420, and the
     * reconstructions themselves when they were kept.
     */
    record Run(List<Packet> packets, String reconMd5, List<byte[]> recon, int width, int height) {
    }

    static Vp9Setup config(int width, int height, int bitrate) {
        return Vp9Setup.of(width, height, new Rational(30, 1), new Rational(1, 30), bitrate, 2, 52, 0, 6,
                false, true, 1, false, 1000, 500, 600, 0, 1);
    }

    static Vp9Setup with(Vp9Setup c, int speed, boolean errorResilient, int layers,
                                      int tiles, int threads) {
        return Vp9Setup.of(c.width(), c.height(), c.frameRate(), c.timeBase(), c.bitrate(),
                c.minQuantizer(), c.maxQuantizer(), c.keyframeInterval(), speed, errorResilient,
                c.adaptiveQuantization(), layers, c.dropFrames(), c.bufferMillis(), c.initialBufferMillis(),
                c.optimalBufferMillis(), tiles, threads);
    }

    static VideoTrack track(int width, int height) {
        return VideoTrack.builder().id(1).codec(Codec.VP9).timeBase(new Rational(1, 30)).width(width).height(height)
                .build();
    }

    static byte[] i420(YuvPicture picture) {
        var w = picture.width();
        var h = picture.height();
        var cw = (w + 1) / 2;
        var ch = (h + 1) / 2;
        var out = new byte[w * h + 2 * cw * ch];
        var pos = 0;
        for (var r = 0; r < h; r++, pos += w) {
            System.arraycopy(picture.y(), r * picture.yStride(), out, pos, w);
        }
        for (var r = 0; r < ch; r++, pos += cw) {
            System.arraycopy(picture.u(), r * picture.uvStride(), out, pos, cw);
        }
        for (var r = 0; r < ch; r++, pos += cw) {
            System.arraycopy(picture.v(), r * picture.uvStride(), out, pos, cw);
        }
        return out;
    }

    static byte[] ivf(int width, int height, List<Packet> packets) {
        var out = new ByteArrayOutputStream();
        var header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        header.put("DKIF".getBytes(StandardCharsets.US_ASCII)).putShort((short) 0).putShort((short) 32)
                .put("VP90".getBytes(StandardCharsets.US_ASCII)).putShort((short) width).putShort((short) height)
                .putInt(30).putInt(1).putInt(packets.size()).putInt(0);
        out.writeBytes(header.array());
        var index = 0L;
        for (var packet : packets) {
            var frame = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            frame.putInt(packet.size()).putLong(index++);
            out.writeBytes(frame.array());
            out.writeBytes(packet.data());
        }
        return out.toByteArray();
    }

    /**
     * Encodes pictures, checking that the module's decoder reproduces the reconstruction of every
     * coded frame.
     */
    static Run encode(Vp9Setup config, IntFunction<YuvPicture> frames, int count, boolean keepRecon,
                      IntPredicate forceKey, IntFunction<Vp9Setup> reconfigure) {
        var packets = new ArrayList<Packet>();
        var recons = new ArrayList<byte[]>();
        var digest = md5();
        var w = config.width();
        var h = config.height();
        var recon = YuvPicture.allocate(w, h, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        try (var encoder = config.encoder(); var decoder = new Vp9Decoder(track(w, h))) {
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
                var expected = i420(recon);
                digest.update(expected);
                if (keepRecon) {
                    recons.add(expected);
                }
                var index = i;
                var decoded = new int[1];
                decoder.decode(packet, frame -> {
                    decoded[0]++;
                    assertArrayEquals(expected, i420((YuvPicture) frame.picture()), "frame " + index);
                });
                assertEquals(1, decoded[0], "frame " + index + " was not shown");
            }
        }
        return new Run(packets, HexFormat.of().formatHex(digest.digest()), recons, w, h);
    }

    static Run encode(Vp9Setup config, List<YuvPicture> frames) {
        return encode(config, frames::get, frames.size(), false, i -> false, null);
    }

    static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Decodes a run with ffmpeg's native VP9 decoder and with libvpx and checks every frame
     * equals the encoder's reconstruction.
     */
    static void assertFfmpegMatches(Run run) throws IOException {
        if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
            return;
        }
        var ivf = Files.createTempFile("vp9rt", ".ivf");
        try {
            Files.write(ivf, ivf(run.width(), run.height(), run.packets()));
            Oracle.assertDecodesCleanly(ivf);
            for (var decoder : new String[]{"vp9", "libvpx-vp9"}) {
                var output = new String(Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error",
                        "-c:v", decoder, "-i", ivf.toString(), "-map", "0:v:0", "-f", "md5", "-pix_fmt", "yuv420p",
                        "-")), StandardCharsets.US_ASCII);
                assertEquals("MD5=" + run.reconMd5(), output.strip(), "ffmpeg's " + decoder + " decode differs");
            }
        } finally {
            Files.deleteIfExists(ivf);
        }
    }

    /** Returns the frames of a cached clip, read lazily, or synthetic frames when it is absent. */
    static IntFunction<YuvPicture> clipOrSynthetic(String name, int width, int height, int frames) throws IOException {
        var clip = Vp9TestVideo.clip(name);
        if (clip != null && Files.size(clip) >= (long) frames * width * height * 3 / 2) {
            return i -> {
                try {
                    return Vp9TestVideo.read(clip, width, height, i, 1).getFirst();
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            };
        }
        return Vp9TestVideo.synthesize(width, height, frames)::get;
    }

    /** Returns whether a packet's uncompressed header declares a key frame. */
    static boolean isKey(Packet packet) {
        var first = packet.data()[0] & 0xFF;
        var profileLow = (first >> 5) & 1;
        var profileHigh = (first >> 4) & 1;
        var profile = (profileHigh << 1) | profileLow;
        var shift = profile == 3 ? 1 : 0;
        var showExisting = (first >> (3 - shift)) & 1;
        return showExisting == 0 && ((first >> (2 - shift)) & 1) == 0;
    }

    @Nested
    @DisplayName("bit-exactness")
    class BitExact {
        static Stream<Arguments> configurations() {
            return Stream.of(
                    Arguments.of("176x144 speed 6", 176, 144, 6, false, 1, 0, 1, 200_000),
                    Arguments.of("176x144 error resilient", 176, 144, 6, true, 1, 0, 1, 200_000),
                    Arguments.of("odd 33x17", 33, 17, 6, true, 1, 0, 1, 50_000),
                    Arguments.of("odd 75x49", 75, 49, 6, false, 1, 0, 1, 80_000),
                    Arguments.of("1x1", 1, 1, 6, false, 1, 0, 1, 10_000),
                    Arguments.of("speed 5", 160, 96, 5, false, 1, 0, 1, 150_000),
                    Arguments.of("speed 7", 160, 96, 7, false, 1, 0, 1, 150_000),
                    Arguments.of("speed 8", 160, 96, 8, false, 1, 0, 1, 150_000),
                    Arguments.of("speed 9", 160, 96, 9, false, 1, 0, 1, 150_000),
                    Arguments.of("two temporal layers", 176, 144, 6, true, 2, 0, 1, 200_000),
                    Arguments.of("three temporal layers", 176, 144, 6, true, 3, 0, 1, 200_000),
                    Arguments.of("four tile columns", 1100, 96, 6, false, 1, 2, 1, 400_000),
                    Arguments.of("four threads", 640, 360, 6, false, 1, 1, 4, 500_000),
                    Arguments.of("high bitrate", 176, 144, 6, false, 1, 0, 1, 5_000_000),
                    Arguments.of("low bitrate", 176, 144, 6, false, 1, 0, 1, 20_000));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("configurations")
        @DisplayName("every frame decodes to the reconstruction with the module's decoder and ffmpeg")
        void decodes(String name, int width, int height, int speed, boolean errorResilient, int layers, int tiles,
                     int threads, int bitrate) throws IOException {
            var config = with(config(width, height, bitrate), speed, errorResilient, layers, tiles, threads);
            var run = encode(config, Vp9TestVideo.synthesize(width, height, 30));
            assertEquals(30, run.packets().size());
            assertTrue(isKey(run.packets().getFirst()));
            assertFfmpegMatches(run);
        }

        @Test
        @DisplayName("a natural clip at 640x480 decodes to the reconstruction, error resilient or not")
        void naturalClip() throws IOException {
            var frames = clipOrSynthetic("johnny480.yuv", 640, 480, 60);
            for (var errorResilient : new boolean[]{false, true}) {
                var run = encode(with(config(640, 480, 600_000), 6, errorResilient, 1, 0, 1), frames, 60, true,
                        i -> false, null);
                assertFfmpegMatches(run);
                var psnr = 0.0;
                for (var i = 0; i < 60; i++) {
                    psnr += Quality.psnr(i420(frames.apply(i)), run.recon().get(i));
                }
                assertTrue(psnr / 60 > 32, "psnr " + psnr / 60);
            }
        }
    }

    @Nested
    @DisplayName("key frames")
    class KeyFrames {
        @Test
        @DisplayName("forced key frames come mid-stream and decode on their own")
        void forced() throws IOException {
            var frames = Vp9TestVideo.synthesize(160, 96, 40);
            var run = encode(config(160, 96, 150_000), frames::get, frames.size(), true, i -> i == 13 || i == 29, null);
            for (var i = 0; i < run.packets().size(); i++) {
                var expected = i == 0 || i == 13 || i == 29;
                assertEquals(expected, isKey(run.packets().get(i)), "frame " + i);
                assertEquals(expected, run.packets().get(i).keyframe(), "frame " + i);
            }
            assertFfmpegMatches(run);
            try (var decoder = new Vp9Decoder(track(160, 96))) {
                for (var i = 29; i < run.packets().size(); i++) {
                    var index = i;
                    decoder.decode(run.packets().get(i), frame ->
                            assertArrayEquals(run.recon().get(index), i420((YuvPicture) frame.picture())));
                }
            }
        }

        @Test
        @DisplayName("requestKeyframe makes the next frame a key frame, from any thread")
        void requested() throws InterruptedException {
            var frames = Vp9TestVideo.synthesize(64, 64, 12);
            try (var encoder = config(64, 64, 100_000).encoder()) {
                for (var i = 0; i < frames.size(); i++) {
                    if (i == 7) {
                        Thread.ofPlatform().start(encoder::requestKeyframe).join();
                    }
                    var packet = encoder.encodeFrame(new VideoFrame(frames.get(i), i, 1, false)).orElse(null);
                    assertEquals(i == 0 || i == 7, packet.keyframe(), "frame " + i);
                }
                assertEquals(2, encoder.stats().keyframesEncoded());
                assertEquals(12, encoder.stats().framesEncoded());
            }
        }

        @Test
        @DisplayName("a request racing with an encode is taken by that frame or the next one")
        void racingRequest() throws InterruptedException {
            var frames = Vp9TestVideo.synthesize(64, 64, 2);
            try (var encoder = config(64, 64, 100_000).encoder()) {
                var pts = 0L;
                encoder.encodeFrame(new VideoFrame(frames.get(0), pts++, 1, false));
                var random = new Random(11);
                for (var round = 0; round < 300; round++) {
                    var start = new CountDownLatch(1);
                    var delay = random.nextInt(20_000);
                    var requester = Thread.ofPlatform().start(() -> {
                        try {
                            start.await();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        var end = System.nanoTime() + delay;
                        while (System.nanoTime() < end) {
                            Thread.onSpinWait();
                        }
                        encoder.requestKeyframe();
                    });
                    start.countDown();
                    var first = encoder.encodeFrame(new VideoFrame(frames.get(1), pts++, 1, false)).orElseThrow();
                    requester.join();
                    var second = encoder.encodeFrame(new VideoFrame(frames.get(0), pts++, 1, false)).orElseThrow();
                    assertTrue(first.keyframe() || second.keyframe(), "request of round " + round + " was lost");
                }
            }
        }

        @Test
        @DisplayName("periodic key frames follow the key frame interval")
        void periodic() throws IOException {
            var c = config(96, 64, 120_000);
            c = Vp9Setup.of(96, 64, c.frameRate(), c.timeBase(), c.bitrate(), 2, 52, 10, 6, false, true, 1,
                    false, 1000, 500, 600, 0, 1);
            var run = encode(c, Vp9TestVideo.synthesize(96, 64, 35));
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
        // average, while the codec's own loss stays under one level.
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

        static Stream<Arguments> colors() {
            return Stream.of(Arguments.of("BT.709 limited", ColorInfo.BT709_LIMITED),
                    Arguments.of("BT.601 full", ColorInfo.JPEG));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("colors")
        @DisplayName("4:2:0 pictures in another matrix or range are converted to the signaled BT.601 limited range")
        void converted(String name, ColorInfo color) {
            var source = tiles();
            var input = ImageOps.toYuv(source, ChromaFormat.YUV420, color);
            var options = Vp9EncoderOptions.builder().bitrate(4_000_000).quantizers(2, 8).build();
            try (var encoder = new Vp9Encoder(128, 64, options); var decoder = new Vp9Decoder(track(128, 64))) {
                var packet = encoder.encodeFrame(new VideoFrame(input, 0, 1, false)).orElseThrow();
                var decoded = new RgbaPicture[1];
                decoder.decode(packet, frame -> decoded[0] = ImageOps.toRgba(frame.picture()));
                var error = tileInteriorError(source, decoded[0]);
                assertTrue(error < 1, name + " input decodes " + error + " levels away from the source");
            }
        }

        @Test
        @DisplayName("BT.601 limited-range 4:2:0 pictures are coded as given")
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
            var options = Vp9EncoderOptions.builder().quantizers(2, 63).build();
            byte[] expected;
            try (var encoder = new Vp9Encoder(128, 64, options)) {
                expected = encoder.encodeFrame(new VideoFrame(input, 0, 1, false)).orElseThrow().data();
            }
            try (var encoder = new Vp9Encoder(128, 64, options)) {
                assertArrayEquals(expected, encoder.encodeFrame(new VideoFrame(unspecified, 0, 1, false)).orElseThrow()
                        .data());
            }
        }

        @Test
        @DisplayName("a stream of converted pictures codes as the same pictures converted beforehand")
        void convertedStream() {
            var bt709 = Vp9TestVideo.synthesize(128, 64, 6).stream()
                    .map(frame -> ImageOps.toYuv(frame, ChromaFormat.YUV420, ColorInfo.BT709_LIMITED)).toList();
            var options = Vp9EncoderOptions.builder().quantizers(2, 63).build();
            try (var converting = new Vp9Encoder(128, 64, options); var direct = new Vp9Encoder(128, 64, options)) {
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
    @DisplayName("lossless")
    class Lossless {
        static Stream<Arguments> configurations() {
            return Stream.of(Arguments.of("176x144 speed 6", 176, 144, 6, 1),
                    Arguments.of("176x144 speed 9", 176, 144, 9, 1),
                    Arguments.of("640x360 on four threads", 640, 360, 6, 4));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("configurations")
        @DisplayName("quantizer 0 codes lossless frames that decode to the source")
        void decodesToSource(String name, int width, int height, int speed, int threads) throws IOException {
            var frames = Vp9TestVideo.synthesize(width, height, 8);
            var setup = Vp9Setup.of(width, height, new Rational(30, 1), new Rational(1, 30), 20_000_000, 0, 0, 0,
                    speed, false, true, 1, false, 1000, 500, 600, 0, threads);
            var run = encode(setup, frames::get, frames.size(), true, i -> false, null);
            assertEquals(frames.size(), run.packets().size(), "no frame may be dropped");
            for (var i = 0; i < frames.size(); i++) {
                assertArrayEquals(i420(frames.get(i)), run.recon().get(i), "frame " + i);
            }
            assertFfmpegMatches(run);
        }
    }

    @Nested
    @DisplayName("threads")
    class Threads {
        @Test
        @DisplayName("the stream is the same for one and several threads, with one or several tiles")
        void deterministic() {
            var frames = Vp9TestVideo.synthesize(640, 360, 20);
            for (var tiles : new int[]{0, 1}) {
                var single = encode(with(config(640, 360, 500_000), 6, false, 1, tiles, 1), frames);
                for (var threads : new int[]{2, 3, 8}) {
                    var multi = encode(with(config(640, 360, 500_000), 6, false, 1, tiles, threads), frames);
                    assertEquals(single.packets().size(), multi.packets().size());
                    for (var i = 0; i < single.packets().size(); i++) {
                        assertArrayEquals(single.packets().get(i).data(), multi.packets().get(i).data(),
                                tiles + " tiles log2, " + threads + " threads, frame " + i);
                    }
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
            var base = Vp9Setup.of(width, height, new Rational(30, 1), new Rational(1, 30), steps[0], 2, 52, 0,
                    6, true, true, 1, dropFrames, 1000, 500, 600, 6, 1);
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
         * converges from its default correction factor there.
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
                report.append(String.format("%d-%d: %d bps (target %d, %+.1f%%, %d dropped)%n", w[1], w[1] + 30, w[2],
                        target, 100.0 * (w[2] - target) / target, w[3]));
            }
            return report.toString();
        }

        static Stream<Arguments> reachable() {
            return Stream.of(Arguments.of("johnny480.yuv", 640, 480, CALL_STEPS),
                    Arguments.of("johnny720.yuv", 1280, 720, new int[]{600_000, 1_500_000, 700_000, 1_200_000}));
        }

        /**
         * libvpx 1.17's real-time encoder, linked into a harness that changes the bitrate with
         * {@code vpx_codec_enc_config_set} and otherwise configured as here, misses these windows by
         * up to 6.7 percent (the 128 kbit/s step at 640x480 settles 6 percent low, and the window
         * that starts a second after an upward step overshoots by up to 6.5 percent); this
         * encoder's windows are within 1.5 percent of libvpx's, so the bound is 8 percent.
         */
        @ParameterizedTest(name = "{1}x{2}")
        @MethodSource("reachable")
        @DisplayName("each second after the first of a reachable bitrate step is within 8 percent of the target")
        void steps(String clip, int width, int height, int[] steps) throws IOException {
            var windows = windows(encodeSteps(clip, width, height, steps, false), steps);
            var report = describe(windows, steps);
            System.out.print(width + "x" + height + System.lineSeparator() + report);
            for (var w : windows) {
                var target = steps[(int) w[0]];
                assertTrue(Math.abs(w[2] - target) <= 0.08 * target, report);
            }
        }

        /**
         * At 1280x720 the clip needs about 140 kbit/s at the largest quantizer, with libvpx as with
         * this encoder, so the 128 kbit/s call step is reachable only by dropping frames: the
         * buffer drains until frames are dropped, which brings the rate to the target.
         */
        @Test
        @DisplayName("the unreachable 128 kbit/s call step at 1280x720 is met by dropping frames")
        void unreachable() throws IOException {
            var count = 270;
            var frames = clipOrSynthetic("johnny720.yuv", 1280, 720, count);
            var config = Vp9Setup.of(1280, 720, new Rational(30, 1), new Rational(1, 30), 128_000, 2, 52, 0,
                    6, true, true, 1, true, 1000, 500, 600, 6, 1);
            var run = encode(config, frames, count, false, i -> false, null);
            assertTrue(run.packets().size() < count, "no frame dropped");
            assertFfmpegMatches(run);
            var bits = 0L;
            for (var packet : run.packets()) {
                if (packet.pts() >= 90) {
                    bits += packet.size() * 8L;
                }
            }
            var rate = bits * 30.0 / (count - 90);
            System.out.printf("1280x720 at 128 kbit/s with frame dropping: %.0f bps, %d of %d frames coded%n", rate,
                    run.packets().size(), count);
            assertTrue(Math.abs(rate - 128_000) <= 0.05 * 128_000, "rate " + rate);
        }

        @Test
        @DisplayName("an unreachable bitrate drops frames only when dropping is enabled")
        void drops() throws IOException {
            var frames = Vp9TestVideo.synthesize(320, 240, 60);
            var c = config(320, 240, 8_000);
            var run = encode(c, frames);
            assertEquals(60, run.packets().size());
            var dropping = Vp9Setup.of(320, 240, c.frameRate(), c.timeBase(), 8_000, 2, 52, 0, 6, true, true,
                    1, true, 1000, 500, 600, 0, 1);
            var dropped = encode(dropping, frames);
            assertTrue(dropped.packets().size() < 60, "no frame dropped");
            assertFfmpegMatches(dropped);
        }
    }

    @Nested
    @DisplayName("hostile input")
    class Hostile {
        static Vp9Setup make(int width, int height, Rational frameRate, int bitrate, int minQ, int maxQ,
                                          int keyInterval, int speed, int layers, int buffer, int initial, int optimal,
                                          int tiles, int threads) {
            return Vp9Setup.of(width, height, frameRate, new Rational(1, 30), bitrate, minQ, maxQ,
                    keyInterval, speed, false, true, layers, false, buffer, initial, optimal, tiles, threads);
        }

        @Test
        @DisplayName("invalid configurations are rejected")
        void invalidConfigurations() {
            var r = new Rational(30, 1);
            assertThrows(IllegalArgumentException.class, () -> make(0, 10, r, 1000, 2, 52, 0, 6, 1, 1000, 500, 600, 0, 1).encoder());
            assertThrows(IllegalArgumentException.class, () -> make(16385, 10, r, 1000, 2, 52, 0, 6, 1, 1000, 500, 600, 0, 1).encoder());
            assertThrows(IllegalArgumentException.class, () -> make(10, 10, r, 0, 2, 52, 0, 6, 1, 1000, 500, 600, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> make(10, 10, r, 1000, 40, 30, 0, 6, 1, 1000, 500, 600, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> make(10, 10, r, 1000, 2, 64, 0, 6, 1, 1000, 500, 600, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> make(10, 10, r, 1000, -1, 52, 0, 6, 1, 1000, 500, 600, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> make(10, 10, r, 1000, 2, 52, -1, 6, 1, 1000, 500, 600, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> make(10, 10, r, 1000, 2, 52, 0, 4, 1, 1000, 500, 600, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> make(10, 10, r, 1000, 2, 52, 0, 10, 1, 1000, 500, 600, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> make(10, 10, r, 1000, 2, 52, 0, 6, 4, 1000, 500, 600, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> make(10, 10, r, 1000, 2, 52, 0, 6, 1, 0, 0, 1, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> make(10, 10, r, 1000, 2, 52, 0, 6, 1, 1000, 2000, 600, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> make(10, 10, r, 1000, 2, 52, 0, 6, 1, 1000, 500, 600, 7, 1));
            assertThrows(IllegalArgumentException.class, () -> make(10, 10, r, 1000, 2, 52, 0, 6, 1, 1000, 500, 600, 0, -1));
            assertThrows(IllegalArgumentException.class, () -> make(10, 10, new Rational(0, 1), 1000, 2, 52, 0, 6, 1, 1000,
                    500, 600, 0, 1));
            assertThrows(NullPointerException.class, () -> make(10, 10, null, 1000, 2, 52, 0, 6, 1, 1000, 500, 600, 0, 1));
            assertThrows(NullPointerException.class, () -> new Vp9Encoder(16, 16, null));
            assertThrows(MediaException.LimitExceeded.class, () -> config(16384, 16384, 1000).encoder());
        }

        @Test
        @DisplayName("wrong sizes, decreasing timestamps, illegal reconfigurations and closed encoders are rejected")
        void misuse() {
            var frame = Vp9TestVideo.synthesize(64, 48, 1).getFirst();
            var encoder = config(64, 48, 100_000).encoder();
            assertThrows(IllegalArgumentException.class, () -> encoder.encodeFrame(
                    new VideoFrame(Vp9TestVideo.synthesize(64, 32, 1).getFirst(), 0, 1, false)));
            encoder.encodeFrame(new VideoFrame(frame, 5, 1, false));
            assertThrows(IllegalArgumentException.class, () -> encoder.encodeFrame(new VideoFrame(frame, 5, 1, false)));
            assertThrows(IllegalArgumentException.class, () -> encoder.encodeFrame(new VideoFrame(frame, 4, 1, false)));
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.reconfigure(encoder.options().toBuilder().timeBase(new Rational(1, 90_000)).build()));
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.reconfigure(with(config(64, 48, 100_000), 6, true, 1, 0, 1).options()));
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.reconfigure(with(config(64, 48, 100_000), 6, false, 2, 0, 1).options()));
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.reconfigure(with(config(64, 48, 100_000), 6, false, 1, 0, 2).options()));
            assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(0));
            assertThrows(NullPointerException.class, () -> encoder.encodeFrame(null));
            assertThrows(NullPointerException.class, () -> encoder.reconfigure(null));
            encoder.close();
            assertThrows(IllegalStateException.class, () -> encoder.encodeFrame(new VideoFrame(frame, 9, 1, false)));
            assertThrows(IllegalStateException.class, () -> encoder.reconfigure(config(64, 48, 100_000).options()));
            assertThrows(IllegalStateException.class, () -> encoder.setTargetBitrate(100_000));
        }

        @Test
        @DisplayName("missing, jittering and jumping timestamps still code every frame")
        void timestamps() {
            var frames = Vp9TestVideo.synthesize(96, 64, 30);
            var random = new Random(7);
            try (var encoder = Vp9Setup.realtime(96, 64, new Rational(30, 1), 100_000).encoder()) {
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
                    if (encoder.encodeFrame(new VideoFrame(frames.get(i), stamp, 0, false)).isPresent()) {
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
                for (var speed : new int[]{5, 9}) {
                    var run = encode(with(config(80, 48, bitrate), speed, false, 1, 0, 1), frames);
                    assertFfmpegMatches(run);
                }
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
                assertEquals(Codec.VP9, encoder.codec());
            }
        }

        @Test
        @DisplayName("the widest and tallest pictures encode")
        void extremeSizes() {
            for (var size : new int[][]{{16384, 2}, {2, 16384}}) {
                var picture = YuvPicture.allocate(size[0], size[1], ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
                Arrays.fill(picture.y(), (byte) 77);
                try (var encoder = with(config(size[0], size[1], 100_000), 6, false, 1, 6, 2).encoder()) {
                    var first = encoder.encodeFrame(new VideoFrame(picture, 0, 1, false)).orElse(null);
                    var second = encoder.encodeFrame(new VideoFrame(picture, 1, 1, false)).orElse(null);
                    assertTrue(first.keyframe());
                    assertFalse(second.keyframe());
                }
            }
        }

        @Test
        @DisplayName("bitrate, frame rate, quantizer, speed and dropping changes on a running stream keep it decodable")
        void reconfigurations() throws IOException {
            var frames = Vp9TestVideo.synthesize(160, 96, 60);
            var base = config(160, 96, 200_000);
            var random = new Random(3);
            var run = encode(base, frames::get, frames.size(), false, i -> i == 33, i -> {
                if (i % 6 != 5) {
                    return null;
                }
                var min = random.nextInt(0, 30);
                return Vp9Setup.of(160, 96, new Rational(random.nextInt(5, 60), 1), base.timeBase(),
                        random.nextInt(1, 3_000_000), min, random.nextInt(min, 64), random.nextInt(0, 20),
                        random.nextInt(5, 10), false, random.nextBoolean(), 1, random.nextBoolean(),
                        random.nextInt(500, 3000), 250, 400, 0, 1);
            });
            assertFfmpegMatches(run);
        }

        @Test
        @DisplayName("encoding finishes in bounded time on hostile streams of settings")
        void bounded() {
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                var frames = Vp9TestVideo.synthesize(48, 32, 10);
                for (var speed = 5; speed <= 9; speed++) {
                    for (var layers = 1; layers <= 3; layers++) {
                        var c = with(config(48, 32, 1 << (speed + 4)), speed, layers > 1, layers, speed & 3, 2);
                        var run = encode(c, frames);
                        assertTrue(run.packets().size() > 0);
                    }
                }
            });
        }

        @Test
        @DisplayName("a dropped frame yields no packet and counts as dropped")
        void droppedFrame() {
            var frames = Vp9TestVideo.synthesize(320, 240, 30);
            var c = Vp9Setup.of(320, 240, new Rational(30, 1), new Rational(1, 30), 2_000, 2, 52, 0, 6, true,
                    true, 1, true, 1000, 0, 600, 0, 1);
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
                assertEquals(30 - dropped, encoder.stats().framesEncoded());
                assertNotNull(encoder.options());
            }
        }
    }
}
