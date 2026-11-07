package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.H264Decoder;
import com.github.auties00.smalt.video.h264.H264DecoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the adaptive quantization modes and strengths, the macroblock tree strength and texture retention in
 * mode decision: the macroblock quantizers each mode assigns on pictures built from flat, textured, dark and
 * bright macroblocks, in both engines, the streams' decoding to the encoder's reconstruction, and the options.
 */
class AdaptiveQuantizationTest {
    private static final int WIDTH = 128;

    private static final int HEIGHT = 64;

    /**
     * The quantizer of an IDR picture of constant quality 30.
     */
    private static final int IDR_QP = 28;

    /**
     * A picture of 8x4 macroblocks: columns 0 and 1 flat mid-gray, 2 and 3 textured with an amplitude, 4 and 5
     * flat near black, 6 and 7 flat near white; every flat area carries the same faint texture.
     */
    static YuvPicture regions(int amplitude, long seed) {
        var picture = YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        var random = new Random(seed);
        for (var row = 0; row < HEIGHT; row++) {
            for (var col = 0; col < WIDTH; col++) {
                var column = col / 16;
                var faint = random.nextInt(3) - 1;
                var value = switch (column) {
                    case 0, 1 -> 128 + faint;
                    case 2, 3 -> 128 + random.nextInt(2 * amplitude + 1) - amplitude;
                    case 4, 5 -> 22 + faint;
                    default -> 200 + faint;
                };
                picture.y()[row * WIDTH + col] = (byte) Math.clamp(value, 0, 255);
            }
        }
        Arrays.fill(picture.u(), (byte) 128);
        Arrays.fill(picture.v(), (byte) 128);
        return picture;
    }

    static H264EncoderOptions.Builder constant() {
        return H264EncoderOptions.builder()
                .rateControl(H264RateControl.CONSTANT_QUALITY)
                .quality(30)
                .lookahead(0)
                .bFrames(0)
                .speed(H264EncoderSpeed.FAST);
    }

    /** Returns the macroblock quantizers of an IDR picture coded with options, in raster order. */
    static int[] quantizers(H264EncoderOptions options, YuvPicture picture) {
        try (var encoder = new H264Encoder(WIDTH, HEIGHT, options)) {
            encoder.encode(new VideoFrame(picture, 0, 0, false), packet -> {
            });
            var bytes = encoder.core().macroblockQuantizers();
            var qps = new int[bytes.length];
            for (var i = 0; i < bytes.length; i++) {
                qps[i] = bytes[i];
            }
            return qps;
        }
    }

    /** Returns the mean quantizer of the macroblocks of two columns of macroblocks. */
    static double columns(int[] qps, int first) {
        var sum = 0.0;
        for (var row = 0; row < HEIGHT / 16; row++) {
            sum += qps[row * (WIDTH / 16) + first] + qps[row * (WIDTH / 16) + first + 1];
        }
        return sum / (2 * (HEIGHT / 16));
    }

    static int range(int[] qps) {
        return Arrays.stream(qps).max().orElseThrow() - Arrays.stream(qps).min().orElseThrow();
    }

    @Test
    @DisplayName("NONE codes every macroblock with the picture's quantizer")
    void none() {
        var qps = quantizers(constant().adaptiveQuantization(H264AdaptiveQuantization.NONE).build(), regions(30, 1));
        assertEquals(0, range(qps), Arrays.toString(qps));
        assertEquals(IDR_QP, qps[0], "an IDR picture of constant quality 30 is coded two steps finer");
    }

    @Test
    @DisplayName("VARIANCE quantizes flat macroblocks finer and textured ones coarser, averaging the picture's")
    void variance() {
        var qps = quantizers(constant().build(), regions(30, 2));
        assertTrue(columns(qps, 0) < IDR_QP && columns(qps, 2) > IDR_QP, Arrays.toString(qps));
        assertEquals(columns(qps, 0), columns(qps, 4), 1.0, "brightness does not matter to VARIANCE");
        var mean = Arrays.stream(qps).average().orElseThrow();
        assertEquals(IDR_QP, mean, 1.0);
    }

    @Test
    @DisplayName("the offsets grow with the strength, and a strength of 0 varies nothing")
    void strength() {
        var picture = regions(12, 3);
        var none = quantizers(constant().adaptiveQuantizationStrength(0).build(), picture);
        var half = quantizers(constant().adaptiveQuantizationStrength(0.5).build(), picture);
        var full = quantizers(constant().build(), picture);
        var twice = quantizers(constant().adaptiveQuantizationStrength(2).build(), picture);
        assertEquals(0, range(none));
        assertTrue(range(half) < range(full) && range(full) < range(twice),
                range(half) + " " + range(full) + " " + range(twice));
        assertTrue(range(twice) <= 12, "offsets stay within six steps either way");
    }

    @Test
    @DisplayName("AUTO_VARIANCE widens the offsets of a picture of little texture contrast and narrows extreme ones")
    void autoVariance() {
        var subtle = regions(3, 4);
        var variance = quantizers(constant().build(), subtle);
        var auto = quantizers(constant().adaptiveQuantization(H264AdaptiveQuantization.AUTO_VARIANCE).build(), subtle);
        assertTrue(range(auto) > range(variance), "subtle: auto " + range(auto) + ", variance " + range(variance));
        var extreme = regions(60, 5);
        variance = quantizers(constant().build(), extreme);
        auto = quantizers(constant().adaptiveQuantization(H264AdaptiveQuantization.AUTO_VARIANCE).build(), extreme);
        assertTrue(range(auto) < range(variance), "extreme: auto " + range(auto) + ", variance " + range(variance));
        assertEquals(columns(auto, 4), columns(auto, 6), 1.0, "brightness does not matter to AUTO_VARIANCE");
    }

    @Test
    @DisplayName("AUTO_VARIANCE_DARK quantizes dark flat macroblocks finer than bright ones of the same texture")
    void dark() {
        var picture = regions(20, 6);
        var qps = quantizers(constant().adaptiveQuantization(H264AdaptiveQuantization.AUTO_VARIANCE_DARK).build(),
                picture);
        assertTrue(columns(qps, 4) <= columns(qps, 6) - 2, Arrays.toString(qps));
        assertEquals(columns(qps, 0), columns(qps, 6), 1.0, "mid-gray and bright are not dark");
        var mean = Arrays.stream(qps).average().orElseThrow();
        assertEquals(IDR_QP, mean, 1.0, "the offsets still average to the picture's quantizer");
    }

    @Test
    @DisplayName("real-time mode codes every macroblock with the picture's quantizer")
    void realtime() {
        var options = H264EncoderOptions.forRealtime().toBuilder().adaptiveQuantization(H264AdaptiveQuantization
                .AUTO_VARIANCE_DARK).build();
        var qps = quantizers(options, regions(30, 7));
        assertEquals(0, range(qps));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(H264AdaptiveQuantization.class)
    @DisplayName("every mode decodes in ffmpeg to exactly the encoder's reconstruction")
    void bitExact(H264AdaptiveQuantization mode) {
        var setup = new H264Setup(WIDTH, HEIGHT, H264EncoderOptions.forFile().toBuilder().bitrate(150_000)
                .frameRate(new Rational(30, 1)).timeBase(new Rational(1, 30)).lookahead(0).bFrames(0)
                .adaptiveQuantization(mode).build());
        var pictures = new ArrayList<YuvPicture>();
        for (var i = 0; i < 24; i++) {
            pictures.add(i % 12 < 6 ? regions(25, i) : EncoderHarness.synthetic(WIDTH, HEIGHT, i, 3, 4.0));
        }
        var encoded = EncoderHarness.encode(setup, pictures, mode == H264AdaptiveQuantization.NONE ? 0 : 1.0);
        EncoderHarness.assertBitExact("aq-" + mode, encoded);
    }

    @Nested
    @DisplayName("in the extended engine")
    class Wide {
        static Yuv16Picture deep(YuvPicture picture) {
            return com.github.auties00.smalt.image.ops.ImageOps.toYuv16(picture, ChromaFormat.YUV420,
                    ColorInfo.BT709_LIMITED, 10);
        }

        static int[] offsets(H264EncoderOptions options, Picture picture) {
            try (var encoder = new H264Encoder(WIDTH, HEIGHT, options)) {
                encoder.encode(new VideoFrame(picture, 0, 0, false), packet -> {
                });
                return encoder.wide().adaptiveOffsets();
            }
        }

        static H264EncoderOptions.Builder high10() {
            return constant().profile(H264Profile.HIGH_10).bitDepth(10);
        }

        @Test
        @DisplayName("NONE leaves every macroblock at the picture's quantizer")
        void none() {
            var offsets = offsets(high10().adaptiveQuantization(H264AdaptiveQuantization.NONE).build(),
                    deep(regions(30, 1)));
            assertEquals(0, offsets.length);
        }

        @Test
        @DisplayName("10-bit samples get the offsets of their 8-bit equivalents")
        void variance() {
            var picture = regions(30, 8);
            var wide = offsets(high10().build(), deep(picture));
            var narrow = quantizers(constant().build(), picture);
            assertEquals(narrow.length, wide.length);
            for (var i = 0; i < wide.length; i++) {
                assertEquals(narrow[i] - IDR_QP, wide[i], 1, "macroblock " + i);
            }
            assertTrue(columns(wide, 0) < 0 && columns(wide, 2) > 0, Arrays.toString(wide));
        }

        @Test
        @DisplayName("AUTO_VARIANCE_DARK quantizes dark macroblocks finer")
        void dark() {
            var offsets = offsets(high10().adaptiveQuantization(H264AdaptiveQuantization.AUTO_VARIANCE_DARK).build(),
                    deep(regions(20, 9)));
            assertTrue(columns(offsets, 4) <= columns(offsets, 6) - 2, Arrays.toString(offsets));
        }

        @Test
        @DisplayName("field pictures get the offsets of their own rows")
        void fields() {
            var options = constant().profile(H264Profile.HIGH).fieldOrder(FieldOrder.TOP_FIRST)
                    .fieldCoding(H264FieldCoding.FIELD_PICTURES).build();
            var offsets = offsets(options, regions(30, 10));
            assertEquals((WIDTH / 16) * (HEIGHT / 32), offsets.length);
            assertTrue(offsets[0] < 0 && offsets[2] > 0, Arrays.toString(offsets));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(H264AdaptiveQuantization.class)
        @DisplayName("a 10-bit stream decodes in Smalt and in ffmpeg to the same samples")
        void decodes(H264AdaptiveQuantization mode) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var options = H264EncoderOptions.forFile().toBuilder().profile(H264Profile.HIGH_10).bitDepth(10)
                    .bitrate(300_000).timeBase(new Rational(1, 30)).adaptiveQuantization(mode).build();
            var packets = new ArrayList<Packet>();
            byte[] avcC;
            try (var encoder = new H264Encoder(WIDTH, HEIGHT, options)) {
                for (var i = 0; i < 16; i++) {
                    var picture = i % 8 < 4 ? regions(25, i) : EncoderHarness.synthetic(WIDTH, HEIGHT, i, 3, 4.0);
                    encoder.encode(new VideoFrame(deep(picture), i, 1, false), packets::add);
                }
                encoder.flush(packets::add);
                avcC = encoder.codecConfig().orElseThrow();
            }
            var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 30)).width(WIDTH)
                    .height(HEIGHT).codecConfig(avcC).build();
            var smalt = new java.io.ByteArrayOutputStream();
            try (var decoder = new H264Decoder(track, H264DecoderOptions.builder().highBitDepth(true).build())) {
                for (var packet : packets) {
                    decoder.decode(packet, frame -> write16(smalt, (Yuv16Picture) frame.picture()));
                }
                decoder.flush(frame -> write16(smalt, (Yuv16Picture) frame.picture()));
            }
            var file = EncoderHarness.write("aq-wide-" + mode + ".264", annexB(avcC, packets));
            var ffmpeg = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-f", "h264",
                    "-i", file.toString(), "-f", "rawvideo", "-pix_fmt", "yuv420p10le", "-"));
            assertEquals(16, packets.size());
            assertArrayEquals(ffmpeg, smalt.toByteArray());
        }

        static void write16(java.io.ByteArrayOutputStream out, Yuv16Picture picture) {
            for (var plane = 0; plane < 3; plane++) {
                var data = plane == 0 ? picture.y() : plane == 1 ? picture.u() : picture.v();
                var stride = plane == 0 ? picture.yStride() : picture.uvStride();
                var width = plane == 0 ? picture.width() : picture.chromaWidth();
                var height = plane == 0 ? picture.height() : picture.chromaHeight();
                for (var row = 0; row < height; row++) {
                    for (var col = 0; col < width; col++) {
                        var value = data[row * stride + col];
                        out.write(value & 0xFF);
                        out.write((value >> 8) & 0xFF);
                    }
                }
            }
        }

        static byte[] annexB(byte[] avcC, List<Packet> packets) {
            var out = new java.io.ByteArrayOutputStream();
            var spsLength = ((avcC[6] & 0xFF) << 8) | (avcC[7] & 0xFF);
            var ppsOffset = 8 + spsLength + 1;
            var ppsLength = ((avcC[ppsOffset] & 0xFF) << 8) | (avcC[ppsOffset + 1] & 0xFF);
            out.writeBytes(new byte[]{0, 0, 0, 1});
            out.write(avcC, 8, spsLength);
            out.writeBytes(new byte[]{0, 0, 0, 1});
            out.write(avcC, ppsOffset + 2, ppsLength);
            for (var packet : packets) {
                for (var nal : HrdVerifier.lengthPrefixed(packet.data())) {
                    out.writeBytes(new byte[]{0, 0, 0, 1});
                    out.writeBytes(nal.data());
                }
            }
            return out.toByteArray();
        }
    }

    @Nested
    @DisplayName("the macroblock tree")
    class Tree {
        static int[] quantizers(double strength, List<YuvPicture> pictures) {
            var options = constant().adaptiveQuantization(H264AdaptiveQuantization.NONE).lookahead(10)
                    .macroblockTreeStrength(strength).build();
            try (var encoder = new H264Encoder(WIDTH, HEIGHT, options)) {
                var result = new int[1][];
                for (var i = 0; i < pictures.size(); i++) {
                    encoder.encode(new VideoFrame(pictures.get(i), i, 0, false), packet -> {
                        if (packet.pts() == 0) {
                            var bytes = encoder.core().macroblockQuantizers();
                            var qps = new int[bytes.length];
                            for (var k = 0; k < bytes.length; k++) {
                                qps[k] = bytes[k];
                            }
                            result[0] = qps;
                        }
                    });
                }
                return result[0];
            }
        }

        /** A static textured background whose left half later pictures reuse and whose right half changes. */
        static List<YuvPicture> reuse() {
            var pictures = new ArrayList<YuvPicture>();
            var background = EncoderHarness.synthetic(WIDTH, HEIGHT, 0, 12, 3.0);
            for (var i = 0; i < 16; i++) {
                var picture = YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
                var noise = new Random(100 + i);
                for (var row = 0; row < HEIGHT; row++) {
                    for (var col = 0; col < WIDTH; col++) {
                        var index = row * WIDTH + col;
                        picture.y()[index] = col < WIDTH / 2 ? background.y()[index] : (byte) noise.nextInt(256);
                    }
                }
                System.arraycopy(background.u(), 0, picture.u(), 0, picture.u().length);
                System.arraycopy(background.v(), 0, picture.v(), 0, picture.v().length);
                pictures.add(picture);
            }
            return pictures;
        }

        @Test
        @DisplayName("lowers the quantizer of reused macroblocks more as the strength grows, and is off at 0")
        void strength() {
            var pictures = reuse();
            var off = quantizers(0, pictures);
            var normal = quantizers(1, pictures);
            var strong = quantizers(2, pictures);
            assertEquals(0, range(off), Arrays.toString(off));
            assertTrue(columns(normal, 0) < columns(normal, 6), Arrays.toString(normal));
            assertTrue(columns(strong, 6) - columns(strong, 0) > columns(normal, 6) - columns(normal, 0),
                    Arrays.toString(normal) + " " + Arrays.toString(strong));
        }
    }

    @Nested
    @DisplayName("texture retention")
    class Texture {
        /** Returns the sum of the absolute differences of horizontally neighboring luma samples. */
        static long detail(byte[] i420) {
            var sum = 0L;
            for (var row = 0; row < HEIGHT; row++) {
                for (var col = 1; col < WIDTH; col++) {
                    sum += Math.abs((i420[row * WIDTH + col] & 0xFF) - (i420[row * WIDTH + col - 1] & 0xFF));
                }
            }
            return sum;
        }

        static double retained(double strength, H264EncoderSpeed speed) {
            var options = constant().quality(34).speed(speed).psychovisualStrength(strength).build();
            var pictures = EncoderHarness.sequence(WIDTH, HEIGHT, 12, 21, 7.0);
            var encoded = EncoderHarness.encode(new H264Setup(WIDTH, HEIGHT, options), pictures);
            var source = 0L;
            var reconstruction = 0L;
            for (var i = 0; i < pictures.size(); i++) {
                source += detail(TwoPassTest.i420(pictures.get(i)));
                reconstruction += detail(encoded.reconstructions().get(i));
            }
            return reconstruction / (double) source;
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = H264EncoderSpeed.class, names = {"FAST", "BALANCED", "SLOW"})
        @DisplayName("keeps more of the source's detail than squared error decisions")
        void keepsDetail(H264EncoderSpeed speed) {
            var plain = retained(0, speed);
            var psy = retained(1, speed);
            assertTrue(psy > plain + 0.01, speed + ": retained " + psy + " with, " + plain + " without");
            assertTrue(retained(2, speed) > plain + 0.01, "the strongest weight keeps more than none");
        }

        @Test
        @DisplayName("changes the skip decisions of the fastest speed")
        void fastest() {
            var options = constant().quality(36).speed(H264EncoderSpeed.FASTEST);
            var pictures = EncoderHarness.sequence(WIDTH, HEIGHT, 8, 22, 5.0);
            var plain = EncoderHarness.encode(new H264Setup(WIDTH, HEIGHT, options.build()), pictures);
            var psy = EncoderHarness.encode(new H264Setup(WIDTH, HEIGHT, options.psychovisualStrength(2).build()),
                    pictures);
            assertNotEquals(plain.totalBytes(), psy.totalBytes());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(doubles = {0.5, 2})
        @DisplayName("streams decode in ffmpeg to exactly the encoder's reconstruction")
        void bitExact(double strength) {
            var options = H264EncoderOptions.forFile().toBuilder().bitrate(200_000).frameRate(new Rational(30, 1))
                    .timeBase(new Rational(1, 30)).lookahead(0).bFrames(0).psychovisualStrength(strength)
                    .speed(H264EncoderSpeed.SLOW).build();
            var encoded = EncoderHarness.encode(new H264Setup(WIDTH, HEIGHT, options),
                    EncoderHarness.sequence(WIDTH, HEIGHT, 20, 23, 6.0));
            EncoderHarness.assertBitExact("psy-" + strength, encoded);
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("default to VARIANCE at strength 1, the macroblock tree at 1 and no texture retention")
        void defaults() {
            var options = H264EncoderOptions.defaults();
            assertEquals(H264AdaptiveQuantization.VARIANCE, options.adaptiveQuantization());
            assertEquals(1, options.adaptiveQuantizationStrength());
            assertEquals(1, options.macroblockTreeStrength());
            assertEquals(0, options.psychovisualStrength());
            assertFalse(options.hrd());
            assertTrue(options.firstPassStats().isEmpty());
        }

        @Test
        @DisplayName("round-trip through the builder and take part in equality")
        void builder() {
            var options = H264EncoderOptions.builder().adaptiveQuantization(H264AdaptiveQuantization.AUTO_VARIANCE)
                    .adaptiveQuantizationStrength(1.5).macroblockTreeStrength(0.5).psychovisualStrength(1).build();
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertNotEquals(options, options.toBuilder().psychovisualStrength(0.9).build());
            assertNotEquals(options, options.toBuilder().macroblockTreeStrength(0.4).build());
            assertNotEquals(options, options.toBuilder().adaptiveQuantizationStrength(1.4).build());
            assertNotEquals(options, options.toBuilder().adaptiveQuantization(H264AdaptiveQuantization.VARIANCE)
                    .build());
            assertTrue(options.toString().contains("adaptiveQuantization=AUTO_VARIANCE"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(doubles = {-0.01, 2.01, Double.NaN, Double.POSITIVE_INFINITY})
        @DisplayName("reject strengths outside [0, 2]")
        void ranges(double value) {
            assertThrows(IllegalArgumentException.class,
                    () -> H264EncoderOptions.builder().adaptiveQuantizationStrength(value).build());
            assertThrows(IllegalArgumentException.class,
                    () -> H264EncoderOptions.builder().macroblockTreeStrength(value).build());
            assertThrows(IllegalArgumentException.class,
                    () -> H264EncoderOptions.builder().psychovisualStrength(value).build());
        }

        @Test
        @DisplayName("reject a null mode")
        void nullMode() {
            assertThrows(NullPointerException.class, () -> H264EncoderOptions.builder().adaptiveQuantization(null));
        }
    }
}
