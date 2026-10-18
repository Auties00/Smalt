package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the public API contract, input conversion and robustness on awkward content: uniform
 * pictures, noise, huge motion, single frames, extreme bitrates, timestamp gaps and the macroblock
 * size limit.
 */
class EncoderBehaviorTest {
    private static final Rational FPS = new Rational(30, 1);

    private static H264Setup cq(int width, int height, H264Profile profile, H264EncoderSpeed speed, int quality) {
        return H264Setup.of(width, height, FPS, FPS.invert(), profile, 0, H264RateControl.CONSTANT_QUALITY, 0, 0, 0,
                quality, 30, speed, ColorInfo.BT709_LIMITED, 1);
    }

    private static YuvPicture uniform(int width, int height, int luma) {
        var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        Arrays.fill(picture.y(), (byte) luma);
        return picture;
    }

    private static YuvPicture noise(int width, int height, long seed) {
        var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        var random = new Random(seed);
        random.nextBytes(picture.y());
        random.nextBytes(picture.u());
        random.nextBytes(picture.v());
        return picture;
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {
        @Test
        @DisplayName("rejects out-of-range values")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> cq(0, 16, H264Profile.MAIN, H264EncoderSpeed.FAST, 26).encoder());
            assertThrows(IllegalArgumentException.class, () -> cq(16, 16_385, H264Profile.MAIN, H264EncoderSpeed.FAST, 26).encoder());
            assertThrows(IllegalArgumentException.class, () -> cq(16, 16, H264Profile.MAIN, H264EncoderSpeed.FAST, 52));
            assertThrows(IllegalArgumentException.class, () -> H264Setup.of(16, 16, FPS, FPS, H264Profile.MAIN, 0,
                    H264RateControl.AVERAGE_BITRATE, 0, 0, 0, 26, 30, H264EncoderSpeed.FAST, ColorInfo.BT709_LIMITED, 1));
            assertThrows(IllegalArgumentException.class, () -> H264Setup.of(16, 16, FPS, FPS, H264Profile.MAIN, 0,
                    H264RateControl.AVERAGE_BITRATE, 1000, 500, 0, 26, 30, H264EncoderSpeed.FAST, ColorInfo.BT709_LIMITED, 1));
            assertThrows(IllegalArgumentException.class, () -> H264Setup.of(16, 16, FPS, FPS, H264Profile.MAIN, 0,
                    H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 26, 30, H264EncoderSpeed.FAST, ColorInfo.BT709_LIMITED, 0));
            assertThrows(IllegalArgumentException.class, () -> H264Setup.of(16, 16, FPS, FPS, H264Profile.MAIN, 0,
                    H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 26, 30, H264EncoderSpeed.FAST,
                    ColorInfo.BT709_LIMITED.withMatrix(MatrixCoefficients.YCGCO), 1));
            assertThrows(IllegalArgumentException.class, () -> H264Setup.of(16, 16, FPS, FPS, H264Profile.MAIN, 35,
                    H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 26, 30, H264EncoderSpeed.FAST, ColorInfo.BT709_LIMITED, 1));
            assertThrows(IllegalArgumentException.class, () -> H264Setup.of(1920, 1080, FPS, FPS.invert(),
                    H264Profile.MAIN, 21, H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 26, 30, H264EncoderSpeed.FAST,
                    ColorInfo.BT709_LIMITED, 1).encoder());
            assertThrows(IllegalArgumentException.class,
                    () -> new H264Encoder(16_385, 16, H264EncoderOptions.defaults()));
        }

        @Test
        @DisplayName("forFile and forRealtime produce the documented settings")
        void factories() {
            var file = H264EncoderOptions.forFile();
            assertEquals(H264EncoderOptions.defaults(), file);
            assertEquals(H264Profile.MAIN, file.profile());
            assertEquals(H264RateControl.AVERAGE_BITRATE, file.rateControl());
            assertTrue(file.maxBitrate().isEmpty());
            assertEquals(Optional.of(Duration.ofSeconds(1)), file.bufferSize());
            assertEquals(OptionalInt.of(60), file.keyframeInterval());
            assertEquals(H264EncoderSpeed.BALANCED, file.speed());
            assertEquals(ColorInfo.BT709_LIMITED, file.color());
            assertEquals(H264PacketFormat.LENGTH_PREFIXED, file.packetFormat());
            assertFalse(file.realtime());
            var message = H264Setup.file(1280, 720, FPS, 1_000_000).resolved();
            assertEquals(2_000_000, message.effectiveMaxBitrate());
            assertEquals(2_000_000, message.effectiveBufferSize());
            assertEquals(60, message.keyframeInterval());
            assertEquals(FPS.invert(), message.timeBase());
            var realtime = H264Setup.realtime(640, 480, FPS, 800_000);
            var options = realtime.options();
            assertEquals(H264Profile.CONSTRAINED_BASELINE, options.profile());
            assertEquals(H264RateControl.CONSTANT_BITRATE, options.rateControl());
            assertEquals(H264EncoderSpeed.FASTEST, options.speed());
            assertTrue(options.keyframeInterval().isEmpty());
            assertEquals(new Rational(1, 90_000), options.timeBase());
            assertEquals(ColorInfo.BT601_LIMITED, options.color());
            assertEquals(H264PacketFormat.ANNEX_B, options.packetFormat());
            assertEquals(12, options.minQuantizer());
            assertEquals(51, options.maxQuantizer());
            assertTrue(options.realtime());
            assertEquals(800_000, realtime.effectiveMaxBitrate());
            assertEquals(400_000, realtime.effectiveBufferSize());
            try (var encoder = realtime.encoder()) {
                assertEquals(Codec.H264, encoder.codec());
                assertEquals(640, encoder.width());
                assertEquals(480, encoder.height());
                assertTrue(encoder.codecConfig().isEmpty(), "Annex B packets carry their parameter sets");
            }
            try (var encoder = H264Setup.file(1280, 720, FPS, 1_000_000).encoder()) {
                assertTrue(encoder.codecConfig().isPresent());
            }
        }
    }

    @Nested
    @DisplayName("encoding contract")
    class Contract {
        @Test
        @DisplayName("rejects pictures of the wrong size, non-increasing timestamps and use after close")
        void misuse() {
            var encoder = cq(32, 32, H264Profile.MAIN, H264EncoderSpeed.FAST, 26).encoder();
            var packets = new ArrayList<Packet>();
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new VideoFrame(uniform(16, 32, 0), 0, 0, false), packets::add));
            encoder.encode(new VideoFrame(uniform(32, 32, 10), 5, 0, false), packets::add);
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new VideoFrame(uniform(32, 32, 10), 5, 0, false), packets::add));
            encoder.close();
            encoder.close();
            assertThrows(IllegalStateException.class,
                    () -> encoder.encode(new VideoFrame(uniform(32, 32, 10), 6, 0, false), packets::add));
        }

        @Test
        @DisplayName("emits one packet per frame with equal timestamps, keeps gaps and flushes nothing")
        void timestamps() {
            var config = H264Setup.of(64, 48, FPS, new Rational(1, 90_000), H264Profile.MAIN, 0,
                    H264RateControl.AVERAGE_BITRATE, 200_000, 0, 0, 26, 30, H264EncoderSpeed.FAST, ColorInfo.BT709_LIMITED, 1);
            var packets = new ArrayList<Packet>();
            long[] pts = {0, 3000, 6000, 30000, 33000, Packet.NO_TIMESTAMP, 99000};
            try (var encoder = config.encoder()) {
                for (var i = 0; i < pts.length; i++) {
                    encoder.encode(new VideoFrame(EncoderHarness.synthetic(64, 48, i, 3, 2), pts[i], i == 1 ? 3000 : 0,
                            false), packets::add);
                    assertEquals(i + 1, packets.size(), "one packet per frame");
                }
                encoder.flush(packets::add);
            }
            assertEquals(pts.length, packets.size());
            long[] expected = {0, 3000, 6000, 30000, 33000, 36000, 99000};
            for (var i = 0; i < packets.size(); i++) {
                assertEquals(expected[i], packets.get(i).pts());
                assertEquals(packets.get(i).pts(), packets.get(i).dts());
                assertEquals(3000, packets.get(i).duration());
            }
        }

        @Test
        @DisplayName("requestKeyframe makes the next frame an IDR picture")
        void requestKeyframe() {
            var config = H264Setup.realtime(96, 64, FPS, 200_000);
            var packets = new ArrayList<Packet>();
            var pictures = EncoderHarness.sequence(96, 64, 12, 4, 1.0);
            try (var encoder = config.encoder()) {
                for (var i = 0; i < pictures.size(); i++) {
                    if (i == 7) {
                        encoder.requestKeyframe();
                    }
                    encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
                }
            }
            for (var i = 0; i < packets.size(); i++) {
                assertEquals(i == 0 || i == 7, packets.get(i).keyframe(), "frame " + i);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(H264EncoderSpeed.class)
        @DisplayName("every thread count produces the same stream as one thread")
        void threadCountIndependence(H264EncoderSpeed speed) {
            var pictures = EncoderHarness.sequence(208, 144, 8, 17, 2.0);
            byte[][] expected = null;
            for (var threads : new int[]{1, 2, 3, 5, 9}) {
                var profile = speed == H264EncoderSpeed.FASTEST ? H264Profile.CONSTRAINED_BASELINE : H264Profile.HIGH;
                var config = H264Setup.of(208, 144, FPS, FPS.invert(), profile, 0,
                        H264RateControl.AVERAGE_BITRATE, 250_000, 0, 0, 26, 30, speed, ColorInfo.BT709_LIMITED, threads);
                var packets = EncoderHarness.encode(config, pictures).packets();
                var data = new byte[packets.size()][];
                for (var i = 0; i < data.length; i++) {
                    data[i] = packets.get(i).data();
                }
                if (expected == null) {
                    expected = data;
                } else {
                    assertEquals(expected.length, data.length, "threads " + threads);
                    for (var i = 0; i < data.length; i++) {
                        assertArrayEquals(expected[i], data[i], "threads " + threads + " packet " + i);
                    }
                }
            }
        }

        @Test
        @DisplayName("parallel analysis gives the same stream on every run and decodes bit-exactly")
        void deterministicThreads() {
            var config = H264Setup.of(320, 240, FPS, FPS.invert(), H264Profile.HIGH, 0,
                    H264RateControl.AVERAGE_BITRATE, 300_000, 0, 0, 26, 30, H264EncoderSpeed.BALANCED, ColorInfo.BT709_LIMITED, 4);
            var pictures = EncoderHarness.sequence(320, 240, 12, 21, 3.0);
            var first = EncoderHarness.encode(config, pictures);
            var second = EncoderHarness.encode(config, pictures);
            assertEquals(first.packets().size(), second.packets().size());
            for (var i = 0; i < first.packets().size(); i++) {
                assertArrayEquals(first.packets().get(i).data(), second.packets().get(i).data(), "packet " + i);
            }
            EncoderHarness.assertBitExact("threads-4", first);
        }
    }

    @Nested
    @DisplayName("input conversion")
    class Input {
        private static RgbaPicture rgba(int width, int height) {
            var picture = RgbaPicture.allocate(width, height, true);
            var pixels = picture.pixels();
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var i = (y * width + x) * 4;
                    pixels[i] = (byte) (x * 255 / Math.max(1, width - 1));
                    pixels[i + 1] = (byte) (y * 255 / Math.max(1, height - 1));
                    pixels[i + 2] = (byte) ((x + y) * 4);
                    pixels[i + 3] = (byte) (x * 7);
                }
            }
            return picture;
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = MatrixCoefficients.class, names = {"BT709", "BT601", "BT2020_NCL"})
        @DisplayName("RGBA input is converted with the configured matrix and decodes close to the source colors")
        void rgbaInput(MatrixCoefficients matrix) {
            var color = ColorInfo.BT709_LIMITED.withMatrix(matrix);
            var config = H264Setup.of(48, 34, FPS, FPS.invert(), H264Profile.HIGH, 0, H264RateControl.CONSTANT_QUALITY,
                    0, 0, 0, 12, 30, H264EncoderSpeed.FAST, color, 1);
            var picture = rgba(48, 34);
            var encoded = EncoderHarness.encode(config, List.of(picture), 0);
            EncoderHarness.assertBitExact("rgba-" + matrix, encoded);
            var file = EncoderHarness.write("rgba-" + matrix + ".264", encoded.annexB());
            var executable = com.github.auties00.smalt.testkit.Oracle.find(
                    com.github.auties00.smalt.testkit.Oracle.Tool.FFMPEG).orElseThrow();
            var matrixName = switch (matrix) {
                case BT601 -> "bt601";
                case BT2020_NCL -> "bt2020";
                default -> "bt709";
            };
            var decoded = com.github.auties00.smalt.testkit.Oracle.run(
                    com.github.auties00.smalt.testkit.Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(1),
                    List.of("-v", "error", "-f", "h264", "-i", file.toString(), "-vf",
                            "scale=in_color_matrix=" + matrixName + ":in_range=tv:out_range=pc:flags=accurate_rnd+full_chroma_int",
                            "-f", "rawvideo", "-pix_fmt", "rgb24", "-"));
            var expected = new byte[48 * 34 * 3];
            for (var i = 0; i < 48 * 34; i++) {
                System.arraycopy(picture.pixels(), i * 4, expected, i * 3, 3);
            }
            assertTrue(executable != null);
            var psnr = Quality.psnr(expected, Arrays.copyOf(decoded, expected.length));
            assertTrue(psnr > 30, "RGB round trip PSNR " + psnr);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ChromaFormat.class)
        @DisplayName("every Y'CbCr layout is accepted at odd sizes and keeps its luma")
        void yuvLayouts(ChromaFormat format) {
            var width = 37;
            var height = 23;
            var picture = YuvPicture.allocate(width, height, format, true, ColorInfo.BT709_LIMITED);
            var random = new Random(format.ordinal());
            for (var i = 0; i < picture.y().length; i++) {
                picture.y()[i] = (byte) (40 + (i % width) * 4);
            }
            if (format.hasChroma()) {
                random.nextBytes(picture.u());
                random.nextBytes(picture.v());
            }
            var encoded = EncoderHarness.encode(cq(width, height, H264Profile.MAIN, H264EncoderSpeed.FAST, 4), List.of(picture), 0);
            EncoderHarness.assertBitExact("layout-" + format, encoded);
            var reconstruction = encoded.reconstructions().getFirst();
            var expected = new byte[width * height];
            var actual = new byte[width * height];
            for (var y = 0; y < height; y++) {
                System.arraycopy(picture.y(), y * width, expected, y * width, width);
                System.arraycopy(reconstruction, y * (width + 1), actual, y * width, width);
            }
            assertTrue(Quality.psnr(expected, actual) > 40, "luma PSNR");
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        @ParameterizedTest(name = "{0}")
        @EnumSource(H264Profile.class)
        @DisplayName("uniform, noisy, single and wildly moving pictures all decode bit-exactly")
        void awkwardContent(H264Profile profile) {
            List<Picture> pictures = new ArrayList<>();
            pictures.add(uniform(80, 48, 0));
            pictures.add(uniform(80, 48, 0));
            pictures.add(uniform(80, 48, 255));
            pictures.add(noise(80, 48, 1));
            pictures.add(noise(80, 48, 2));
            for (var i = 0; i < 6; i++) {
                pictures.add(EncoderHarness.synthetic(80, 48, i * 37, 5, 0));
            }
            for (var speed : H264EncoderSpeed.values()) {
                var config = H264Setup.of(80, 48, FPS, FPS.invert(), profile, 0, H264RateControl.AVERAGE_BITRATE,
                        100_000, 0, 0, 26, 30, speed, ColorInfo.BT709_LIMITED, 1);
                EncoderHarness.assertBitExact("awkward-" + profile + "-" + speed,
                        EncoderHarness.encode(config, pictures));
                EncoderHarness.assertBitExact("single-" + profile + "-" + speed,
                        EncoderHarness.encode(config, List.of(noise(80, 48, 9))));
            }
        }

        @Test
        @DisplayName("a tiny bitrate saturates the quantizer at 51 and still decodes")
        void tinyBitrate() {
            var config = H264Setup.of(640, 368, FPS, FPS.invert(), H264Profile.MAIN, 0,
                    H264RateControl.AVERAGE_BITRATE, 5_000, 0, 0, 26, 30, H264EncoderSpeed.FASTEST, ColorInfo.BT709_LIMITED, 1);
            var pictures = new ArrayList<YuvPicture>();
            for (var i = 0; i < 10; i++) {
                pictures.add(noise(640, 368, i));
            }
            try (var encoder = config.encoder()) {
                var packets = new ArrayList<Packet>();
                for (var i = 0; i < pictures.size(); i++) {
                    encoder.encode(new VideoFrame(pictures.get(i), i, 0, false), packets::add);
                }
                assertEquals(51, encoder.core().lastQp());
            }
            EncoderHarness.assertBitExact("tiny-bitrate", EncoderHarness.encode(config, pictures));
        }

        @Test
        @DisplayName("a huge bitrate settles at the quantizer floor")
        void hugeBitrate() {
            var config = H264Setup.of(64, 64, FPS, FPS.invert(), H264Profile.CONSTRAINED_BASELINE, 0,
                    H264RateControl.AVERAGE_BITRATE, 50_000_000, 0, 0, 26, 30, H264EncoderSpeed.FAST, ColorInfo.BT709_LIMITED, 1);
            var pictures = EncoderHarness.sequence(64, 64, 20, 8, 2.0);
            try (var encoder = config.encoder()) {
                var packets = new ArrayList<Packet>();
                for (var i = 0; i < pictures.size(); i++) {
                    encoder.encode(new VideoFrame(pictures.get(i), i, 0, false), packets::add);
                }
                assertEquals(10, encoder.core().lastQp());
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(H264Profile.class)
        @DisplayName("macroblocks over the size limit after analysis are coded as I_PCM on a second pass")
        void secondPassPcm(H264Profile profile) {
            var pictures = EncoderHarness.sequence(96, 64, 4, 23, 6.0);
            var single = EncoderHarness.encode(cq(96, 64, profile, H264EncoderSpeed.BALANCED, 18), pictures, 0, 700);
            var parallel = EncoderHarness.encode(H264Setup.of(96, 64, FPS, FPS.invert(), profile, 0,
                    H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 18, 30, H264EncoderSpeed.BALANCED, ColorInfo.BT709_LIMITED, 2), pictures,
                    0, 700);
            assertTrue(single.typeCounts()[3] > 0, "I_PCM macroblocks were produced");
            for (var i = 0; i < single.packets().size(); i++) {
                assertArrayEquals(single.packets().get(i).data(), parallel.packets().get(i).data(), "packet " + i);
            }
            EncoderHarness.assertBitExact("second-pass-pcm-" + profile, single);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(H264Profile.class)
        @DisplayName("the I_PCM fallback gives the same stream with parallel analysis")
        void pcmFallbackThreads(H264Profile profile) {
            var pictures = List.of(noise(48, 48, 5), noise(48, 48, 6));
            var single = EncoderHarness.encode(cq(48, 48, profile, H264EncoderSpeed.FAST, 0), pictures, 0);
            var parallel = EncoderHarness.encode(H264Setup.of(48, 48, FPS, FPS.invert(), profile, 0,
                    H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 0, 30, H264EncoderSpeed.FAST, ColorInfo.BT709_LIMITED, 3), pictures, 0);
            assertTrue(single.typeCounts()[3] > 0, "I_PCM macroblocks were produced");
            assertEquals(single.packets().size(), parallel.packets().size());
            for (var i = 0; i < single.packets().size(); i++) {
                assertArrayEquals(single.packets().get(i).data(), parallel.packets().get(i).data(), "packet " + i);
            }
            EncoderHarness.assertBitExact("pcm-threads-" + profile, parallel);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(H264Profile.class)
        @DisplayName("noise at quantizer 0 exceeds the macroblock size limit and falls back to I_PCM losslessly")
        void pcmFallback(H264Profile profile) {
            var pictures = List.of(noise(48, 32, 3), noise(48, 32, 4));
            var encoded = EncoderHarness.encode(cq(48, 32, profile, H264EncoderSpeed.FAST, 0), pictures, 0);
            EncoderHarness.assertBitExact("pcm-" + profile, encoded);
            assertTrue(encoded.typeCounts()[3] > 0, "I_PCM macroblocks were produced");
            var source = new byte[48 * 32];
            System.arraycopy(pictures.getFirst().y(), 0, source, 0, source.length);
            var reconstruction = Arrays.copyOf(encoded.reconstructions().getFirst(), source.length);
            assertTrue(Quality.psnr(source, reconstruction) > 45, "near-lossless at quantizer 0");
        }
    }
}
