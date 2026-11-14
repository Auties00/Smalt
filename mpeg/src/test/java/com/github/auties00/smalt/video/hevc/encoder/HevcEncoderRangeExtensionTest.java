package com.github.auties00.smalt.video.hevc.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;
import com.github.auties00.smalt.video.hevc.internal.HevcNal;
import com.github.auties00.smalt.video.hevc.internal.HevcPps;
import com.github.auties00.smalt.video.hevc.internal.HevcSps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Covers the format range extensions profiles (4:2:2 and 4:4:4 chroma) and lossless coding with
 * {@code cu_transquant_bypass_flag}: every stream is decoded by Smalt's decoder, which verifies the MD5 decoded
 * picture hashes against its own reconstruction, and by ffmpeg as a black box, whose output must equal Smalt's
 * sample for sample; lossless streams must reproduce their input exactly.
 */
@DisplayName("HEVC encoder range extensions and lossless coding")
class HevcEncoderRangeExtensionTest {
    private static final Tolerance EXACT = Tolerance.exact();

    /** Returns a sequence of synthetic Y'CbCr pictures of a chroma format and bit depth. */
    static List<Picture> sequence(int width, int height, int frames, long seed, int depth, ChromaFormat format) {
        var pictures = new ArrayList<Picture>();
        for (var f = 0; f < frames; f++) {
            pictures.add(picture(width, height, f, seed, depth, format));
        }
        return pictures;
    }

    /** Returns one moving picture with gradients, edges, texture and noise in every plane. */
    static Picture picture(int width, int height, int f, long seed, int depth, ChromaFormat format) {
        var random = new Random(seed * 1_000_003L + f);
        var max = (1 << depth) - 1;
        var scale = 1 << (depth - 8);
        var cw = format.chromaWidth(width);
        var ch = format.chromaHeight(height);
        var luma = new int[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var value = 110 + 50 * Math.sin((x + 3 * f) / 9.0) * Math.cos((y - f) / 13.0)
                        + 20 * Math.sin((x * 3 + y * 2) / 5.0) + random.nextInt(7) - 3;
                if (((x + f * 4 + (int) (seed & 7)) / 12 + (y + f) / 12) % 4 == 0) {
                    value += 50;
                }
                luma[y * width + x] = Math.clamp(Math.round(value * scale), 0, max);
            }
        }
        var cb = new int[cw * ch];
        var cr = new int[cw * ch];
        for (var y = 0; y < ch; y++) {
            for (var x = 0; x < cw; x++) {
                var edge = ((x + 2 * f) / 7 + y / 9) % 3 == 0 ? 30 : 0;
                cb[y * cw + x] = Math.clamp(Math.round((128 + 40 * Math.sin((x + f) / 6.0) + edge) * scale), 0, max);
                cr[y * cw + x] = Math.clamp(Math.round((128 + 35 * Math.cos((y + 2 * f) / 7.0) - edge
                        + random.nextInt(5) - 2) * scale), 0, max);
            }
        }
        if (depth == 8) {
            var picture = YuvPicture.allocate(width, height, format, false, ColorInfo.BT709_LIMITED);
            fill8(picture.y(), picture.yStride(), luma, width, height);
            fill8(picture.u(), picture.uvStride(), cb, cw, ch);
            fill8(picture.v(), picture.uvStride(), cr, cw, ch);
            return picture;
        }
        var picture = Yuv16Picture.allocate(width, height, format, depth, false, ColorInfo.BT709_LIMITED);
        fill16(picture.y(), picture.yStride(), luma, width, height);
        fill16(picture.u(), picture.uvStride(), cb, cw, ch);
        fill16(picture.v(), picture.uvStride(), cr, cw, ch);
        return picture;
    }

    private static void fill8(byte[] plane, int stride, int[] values, int width, int height) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                plane[y * stride + x] = (byte) values[y * width + x];
            }
        }
    }

    private static void fill16(short[] plane, int stride, int[] values, int width, int height) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                plane[y * stride + x] = (short) values[y * width + x];
            }
        }
    }

    /** Returns the ffmpeg pixel format of decoded pictures of a chroma format and depth. */
    static String pixelFormat(ChromaFormat format, int depth) {
        var name = switch (format) {
            case YUV420 -> "yuv420p";
            case YUV422 -> "yuv422p";
            case YUV444 -> "yuv444p";
            default -> throw new IllegalArgumentException(format.toString());
        };
        return depth == 8 ? name : name + depth + "le";
    }

    /** Encodes, decodes with Smalt and ffmpeg, checks both decodes agree, and returns Smalt's frames. */
    static List<com.github.auties00.smalt.video.VideoFrame> roundTrip(String name, int width, int height,
                                                                     HevcEncoderOptions options,
                                                                     List<Picture> pictures) {
        var encoded = HevcEncoderHarness.encode(width, height, options, pictures);
        var decoded = HevcEncoderHarness.decode(encoded);
        assertEquals(pictures.size(), decoded.size(), name + ": frames");
        for (var frame : decoded) {
            var picture = frame.picture();
            assertEquals(options.chromaFormat(), switch (picture) {
                case YuvPicture p -> p.chromaFormat();
                case Yuv16Picture p -> p.chromaFormat();
                default -> fail("unexpected picture " + picture);
            }, name + ": decoded chroma format");
        }
        assertArrayEquals(HevcEncoderHarness.decode(encoded, 4).stream()
                        .map(frame -> HevcEncoderHarness.raw(frame.picture())).toArray(),
                decoded.stream().map(frame -> HevcEncoderHarness.raw(frame.picture())).toArray(),
                name + ": the wavefront decode differs");
        var reference = HevcEncoderHarness.ffmpeg(name, encoded.annexB(),
                pixelFormat(options.chromaFormat(), options.bitDepth()));
        EXACT.assertWithin(name + ": ffmpeg", HevcEncoderHarness.raw(decoded), reference);
        return decoded;
    }

    static Stream<Arguments> formats() {
        return Stream.of(
                Arguments.of(HevcProfile.MAIN_444, ChromaFormat.YUV444, 8),
                Arguments.of(HevcProfile.MAIN_444_10, ChromaFormat.YUV444, 10),
                Arguments.of(HevcProfile.MAIN_444_10, ChromaFormat.YUV422, 10),
                Arguments.of(HevcProfile.MAIN_422_10, ChromaFormat.YUV422, 10),
                Arguments.of(HevcProfile.MAIN_422_10, ChromaFormat.YUV422, 8),
                Arguments.of(HevcProfile.MAIN_444, ChromaFormat.YUV420, 8));
    }

    @Nested
    @DisplayName("format range extensions")
    class RangeExtensions {
        @ParameterizedTest(name = "{0} {1} {2}-bit {3}")
        @MethodSource("cases")
        @DisplayName("lossy sequences decode identically in Smalt and ffmpeg and keep their quality")
        void lossy(HevcProfile profile, ChromaFormat format, int depth, HevcEncoderSpeed speed) {
            var options = HevcEncoderOptions.builder().profile(profile).chromaFormat(format).bitDepth(depth)
                    .rateControl(HevcRateControl.CONSTANT_QUALITY).quality(27).speed(speed).bFrames(2)
                    .keyframeInterval(7).pictureHash(HevcPictureHash.MD5).build();
            var width = 88;
            var height = 72;
            var pictures = sequence(width, height, 9, 7, depth, format);
            var name = "rext-" + profile + "-" + format + "-" + depth + "-" + speed;
            var decoded = roundTrip(name, width, height, options, pictures);
            for (var i = 0; i < decoded.size(); i++) {
                assertTrue(chromaPsnr(decoded.get(i).picture(), pictures.get(i)) > 33, name + ": chroma PSNR of " + i);
                assertTrue(HevcEncoderHarness.psnr(decoded.get(i).picture(), pictures.get(i)) > 33,
                        name + ": luma PSNR of " + i);
            }
        }

        static Stream<Arguments> cases() {
            var arguments = new ArrayList<Arguments>();
            for (var format : formats().toList()) {
                for (var speed : HevcEncoderSpeed.values()) {
                    var values = format.get();
                    arguments.add(Arguments.of(values[0], values[1], values[2], speed));
                }
            }
            return arguments.stream();
        }

        @ParameterizedTest(name = "{0}x{1}")
        @MethodSource("sizes")
        @DisplayName("odd picture sizes keep their exact width and height when the chroma format allows it")
        void oddSizes(int width, int height) {
            for (var format : List.of(ChromaFormat.YUV444, ChromaFormat.YUV422)) {
                var options = HevcEncoderOptions.builder().profile(HevcProfile.MAIN_444).chromaFormat(format)
                        .rateControl(HevcRateControl.CONSTANT_QUALITY).quality(24).bFrames(0)
                        .pictureHash(HevcPictureHash.MD5).build();
                var pictures = sequence(width, height, 2, 3, 8, format);
                var decoded = HevcEncoderHarness.decode(HevcEncoderHarness.encode(width, height, options, pictures));
                var picture = decoded.getFirst().picture();
                assertEquals(format == ChromaFormat.YUV444 ? width : (width + 1) & ~1, picture.width());
                assertEquals(height, picture.height());
            }
        }

        static Stream<Arguments> sizes() {
            return Stream.of(Arguments.of(37, 29), Arguments.of(65, 9), Arguments.of(7, 71), Arguments.of(1, 1));
        }

        @Test
        @DisplayName("4:2:0 input is upsampled to a 4:4:4 stream")
        void resampled() {
            var options = HevcEncoderOptions.builder().profile(HevcProfile.MAIN_444).chromaFormat(ChromaFormat.YUV444)
                    .rateControl(HevcRateControl.CONSTANT_QUALITY).quality(22).bFrames(0)
                    .pictureHash(HevcPictureHash.MD5).build();
            var pictures = new ArrayList<Picture>(HevcEncoderHarness.sequence(48, 40, 2, 5));
            var decoded = roundTrip("rext-resampled", 48, 40, options, pictures);
            assertTrue(HevcEncoderHarness.psnr(decoded.getFirst().picture(), pictures.getFirst()) > 35);
        }
    }

    @Nested
    @DisplayName("lossless coding")
    class Lossless {
        @ParameterizedTest(name = "{0} {1} {2}-bit {3}")
        @MethodSource("cases")
        @DisplayName("every decoded picture equals its input")
        void exact(HevcProfile profile, ChromaFormat format, int depth, HevcEncoderSpeed speed) {
            var options = HevcEncoderOptions.builder().profile(profile).chromaFormat(format).bitDepth(depth)
                    .lossless(true).speed(speed).bFrames(2).keyframeInterval(5).pictureHash(HevcPictureHash.MD5)
                    .build();
            var width = 80;
            var height = 56;
            var pictures = sequence(width, height, 7, 11, depth, format);
            var name = "lossless-" + profile + "-" + format + "-" + depth + "-" + speed;
            var decoded = roundTrip(name, width, height, options, pictures);
            for (var i = 0; i < pictures.size(); i++) {
                EXACT.assertWithin(name + ": picture " + i, HevcEncoderHarness.raw(pictures.get(i)),
                        HevcEncoderHarness.raw(decoded.get(i).picture()));
            }
        }

        static Stream<Arguments> cases() {
            var arguments = new ArrayList<Arguments>();
            var formats = new ArrayList<>(formats().map(Arguments::get).toList());
            formats.add(new Object[]{HevcProfile.MAIN, ChromaFormat.YUV420, 8});
            formats.add(new Object[]{HevcProfile.MAIN_10, ChromaFormat.YUV420, 10});
            for (var values : formats) {
                for (var speed : HevcEncoderSpeed.values()) {
                    arguments.add(Arguments.of(values[0], values[1], values[2], speed));
                }
            }
            return arguments.stream();
        }

        @Test
        @DisplayName("noise, flat areas and full-range extremes stay exact at odd sizes")
        void extremes() {
            var random = new Random(77);
            for (var format : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444)) {
                // odd where the chroma format does not halve the dimension, as the decoder rounds the others up
                var width = format == ChromaFormat.YUV444 ? 45 : 46;
                var height = format == ChromaFormat.YUV420 ? 34 : 33;
                var pictures = new ArrayList<Picture>();
                for (var f = 0; f < 3; f++) {
                    var picture = Yuv16Picture.allocate(width, height, format, 10, false,
                            ColorInfo.BT709_LIMITED.withRange(ColorRange.FULL));
                    for (var plane : List.of(picture.y(), picture.u(), picture.v())) {
                        for (var i = 0; i < plane.length; i++) {
                            plane[i] = (short) switch (f) {
                                case 0 -> random.nextInt(1024);
                                case 1 -> (i & 1) == 0 ? 0 : 1023;
                                default -> 512;
                            };
                        }
                    }
                    pictures.add(picture);
                }
                var options = HevcEncoderOptions.builder().profile(HevcProfile.MAIN_444_10).chromaFormat(format)
                        .bitDepth(10).color(ColorInfo.BT709_LIMITED.withRange(ColorRange.FULL)).lossless(true)
                        .bFrames(1)
                        .pictureHash(HevcPictureHash.MD5).build();
                var decoded = roundTrip("lossless-extremes-" + format, width, height, options, pictures);
                for (var i = 0; i < pictures.size(); i++) {
                    EXACT.assertWithin(format + ": picture " + i, HevcEncoderHarness.raw(pictures.get(i)),
                            HevcEncoderHarness.raw(decoded.get(i).picture()));
                }
            }
        }

        @Test
        @DisplayName("8-bit RGB with the full-range identity matrix comes back as its G, B and R samples")
        void rgb() {
            var width = 61;
            var height = 47;
            var random = new Random(5);
            var rgba = RgbaPicture.allocate(width, height, false);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var at = y * rgba.stride() + x * 4;
                    rgba.pixels()[at] = (byte) (x * 4 + random.nextInt(9));
                    rgba.pixels()[at + 1] = (byte) (y * 5 ^ x);
                    rgba.pixels()[at + 2] = (byte) random.nextInt(256);
                    rgba.pixels()[at + 3] = (byte) 255;
                }
            }
            var color = ColorInfo.JPEG.withMatrix(MatrixCoefficients.IDENTITY);
            var options = HevcEncoderOptions.forStillPicture().toBuilder().profile(HevcProfile.MAIN_444_STILL_PICTURE)
                    .chromaFormat(ChromaFormat.YUV444).color(color).lossless(true).pictureHash(HevcPictureHash.MD5)
                    .build();
            var encoded = HevcEncoderHarness.encode(width, height, options, List.of(rgba));
            var picture = (YuvPicture) HevcEncoderHarness.decode(encoded).getFirst().picture();
            assertEquals(MatrixCoefficients.IDENTITY, picture.color().matrix());
            assertEquals(ColorRange.FULL, picture.color().range());
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var at = y * rgba.stride() + x * 4;
                    assertEquals(rgba.pixels()[at + 1], picture.y()[y * picture.yStride() + x], "G at " + x + "," + y);
                    assertEquals(rgba.pixels()[at + 2], picture.u()[y * picture.uvStride() + x], "B at " + x + "," + y);
                    assertEquals(rgba.pixels()[at], picture.v()[y * picture.uvStride() + x], "R at " + x + "," + y);
                }
            }
            // ffmpeg outputs identity-matrix streams as planar G, B, R
            var reference = HevcEncoderHarness.ffmpeg("lossless-rgb", encoded.annexB(), "gbrp");
            EXACT.assertWithin("ffmpeg gbrp", HevcEncoderHarness.raw(picture), reference);
        }

        @Test
        @DisplayName("a lossless stream is far smaller than the raw samples and costs more than a lossy one")
        void compresses() {
            var pictures = sequence(96, 64, 4, 9, 8, ChromaFormat.YUV444);
            var lossless = HevcEncoderHarness.encode(96, 64, HevcEncoderOptions.builder().profile(HevcProfile.MAIN_444)
                    .chromaFormat(ChromaFormat.YUV444).lossless(true).build(), pictures);
            var lossy = HevcEncoderHarness.encode(96, 64, HevcEncoderOptions.builder().profile(HevcProfile.MAIN_444)
                    .chromaFormat(ChromaFormat.YUV444).rateControl(HevcRateControl.CONSTANT_QUALITY).quality(30)
                    .build(), pictures);
            var raw = 4L * 96 * 64 * 3;
            assertTrue(lossless.bytes() < raw * 3 / 4, "lossless " + lossless.bytes() + " of " + raw);
            assertTrue(lossy.bytes() < lossless.bytes(), "lossy " + lossy.bytes() + " lossless " + lossless.bytes());
        }

        @Test
        @DisplayName("the parameter sets enable transquant bypass and disable the loop filters")
        void bypassSignaling() {
            var options = HevcEncoderOptions.builder().lossless(true).build();
            try (var encoder = new HevcEncoder(32, 32, options)) {
                assertThrows(IllegalStateException.class, () -> encoder.setTargetBitrate(500_000));
            }
            var encoded = HevcEncoderHarness.encode(32, 32, options, HevcEncoderHarness.sequence(32, 32, 1, 1));
            var sets = parameterSets(encoded.annexB());
            assertFalse(sets.sps.saoEnabled, "sample_adaptive_offset_enabled_flag");
            assertTrue(sets.pps.transquantBypassEnabled, "transquant_bypass_enabled_flag");
            assertTrue(sets.pps.deblockingDisabled, "pps_deblocking_filter_disabled_flag");
            assertFalse(sets.pps.signDataHiding, "sign_data_hiding_enabled_flag");
        }
    }

    @Nested
    @DisplayName("the HM reference decoder")
    class Reference {
        @ParameterizedTest(name = "{0} {1} {2}-bit lossless={3}")
        @MethodSource("cases")
        @DisplayName("TAppDecoder verifies every MD5 picture hash and outputs Smalt's decode, the source when lossless")
        void hm(HevcProfile profile, ChromaFormat format, int depth, boolean lossless) {
            Oracle.assume(Oracle.Tool.HMDEC);
            var options = HevcEncoderOptions.builder().profile(profile).chromaFormat(format).bitDepth(depth)
                    .lossless(lossless).rateControl(HevcRateControl.CONSTANT_QUALITY).quality(29)
                    .speed(HevcEncoderSpeed.BALANCED).bFrames(2).keyframeInterval(6).pictureHash(HevcPictureHash.MD5)
                    .build();
            var width = 72;
            var height = 40;
            var pictures = sequence(width, height, 8, 13, depth, format);
            var encoded = HevcEncoderHarness.encode(width, height, options, pictures);
            var decoded = HevcEncoderHarness.decode(encoded);
            var name = "hm-" + profile + "-" + format + "-" + depth + (lossless ? "-lossless" : "");
            var stream = HevcEncoderHarness.write(name + ".hevc", encoded.annexB());
            var output = stream.resolveSibling(name + ".yuv");
            var result = Oracle.execute(Oracle.find(Oracle.Tool.HMDEC).orElseThrow(), Duration.ofMinutes(5), null,
                    List.of("-b", stream.toString(), "-o", output.toString(), "-d", "0"));
            var log = new String(result.stdout(), StandardCharsets.UTF_8);
            assertEquals(0, result.exitCode(), name + ": TAppDecoder failed: " + log + result.stderr());
            assertFalse(log.contains("ERROR"), name + ": " + log);
            // TAppDecoder logs one "POC ..." line per picture, ending in "[MD5:<hashes>,(OK)]" when the hashes verify
            var verified = log.lines().filter(line -> line.startsWith("POC") && line.contains(",(OK)]")).count();
            assertEquals(pictures.size(), verified, name + ": verified hashes in " + log);
            byte[] reference;
            try {
                reference = Files.readAllBytes(output);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            EXACT.assertWithin(name + ": TAppDecoder", reference, HevcEncoderHarness.raw(decoded));
            if (lossless) {
                var source = new java.io.ByteArrayOutputStream();
                pictures.forEach(picture -> source.writeBytes(HevcEncoderHarness.raw(picture)));
                EXACT.assertWithin(name + ": source", source.toByteArray(), reference);
            }
        }

        static Stream<Arguments> cases() {
            var arguments = new ArrayList<Arguments>();
            for (var values : formats().map(Arguments::get).toList()) {
                arguments.add(Arguments.of(values[0], values[1], values[2], false));
                arguments.add(Arguments.of(values[0], values[1], values[2], true));
            }
            arguments.add(Arguments.of(HevcProfile.MAIN, ChromaFormat.YUV420, 8, true));
            arguments.add(Arguments.of(HevcProfile.MAIN_10, ChromaFormat.YUV420, 10, true));
            return arguments.stream();
        }
    }

    @Nested
    @DisplayName("signaling")
    class Signaling {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = HevcProfile.class, names = {"MAIN_422_10", "MAIN_444", "MAIN_444_10",
                "MAIN_444_STILL_PICTURE"})
        @DisplayName("the range extensions profiles signal general_profile_idc 4 and the constraint flags of Table A.2")
        void profile(HevcProfile profile) {
            var format = profile == HevcProfile.MAIN_422_10 ? ChromaFormat.YUV422 : ChromaFormat.YUV444;
            var builder = (profile.stillPicture() ? HevcEncoderOptions.forStillPicture().toBuilder()
                    : HevcEncoderOptions.builder()).profile(profile).chromaFormat(format);
            var encoded = HevcEncoderHarness.encode(40, 24, builder.build(), sequence(40, 24, 1, 2, 8, format));
            var sets = parameterSets(encoded.annexB());
            assertEquals(4, sets.sps.profileIdc);
            assertEquals(format == ChromaFormat.YUV444 ? 3 : 2, sets.sps.chromaFormatIdc);
            // the 48 constraint bits after general_profile_compatibility_flag[32]
            var bits = constraintBits(encoded.config());
            long expected = switch (profile) {
                case MAIN_422_10 -> 0b110100001;
                case MAIN_444 -> 0b111000001;
                case MAIN_444_10 -> 0b110000001;
                default -> 0b111000111;
            };
            assertEquals(0b1001L, bits >>> 44, "progressive and frame-only source");
            assertEquals(expected, (bits >>> 35) & 0x1FF, "Table A.2 constraint flags");
            assertEquals(0, bits & ((1L << 35) - 1), "reserved bits");
            var compatibility = ((encoded.config()[2] & 0xFF) << 24) | ((encoded.config()[3] & 0xFF) << 16)
                    | ((encoded.config()[4] & 0xFF) << 8) | (encoded.config()[5] & 0xFF);
            assertEquals(1 << (31 - 4), compatibility, "general_profile_compatibility_flag[4] only");
            assertEquals(4, encoded.config()[1] & 0x1F, "configuration record profile");
            assertEquals(format == ChromaFormat.YUV444 ? 3 : 2, encoded.config()[16] & 3,
                    "configuration record chromaFormat");
        }

        @Test
        @DisplayName("the builder checks the chroma format against the profile and the identity matrix against 4:4:4")
        void validation() {
            assertThrows(IllegalArgumentException.class,
                    () -> HevcEncoderOptions.builder().chromaFormat(ChromaFormat.YUV444).build());
            assertThrows(IllegalArgumentException.class, () -> HevcEncoderOptions.builder()
                    .profile(HevcProfile.MAIN_422_10).chromaFormat(ChromaFormat.YUV444).build());
            assertThrows(IllegalArgumentException.class, () -> HevcEncoderOptions.builder()
                    .profile(HevcProfile.MAIN_444).chromaFormat(ChromaFormat.MONOCHROME).build());
            assertThrows(IllegalArgumentException.class, () -> HevcEncoderOptions.builder()
                    .profile(HevcProfile.MAIN_444).chromaFormat(ChromaFormat.YUV422)
                    .color(ColorInfo.JPEG.withMatrix(MatrixCoefficients.IDENTITY)).build());
            assertThrows(IllegalArgumentException.class, () -> HevcEncoderOptions.builder()
                    .profile(HevcProfile.MAIN_444).bitDepth(10).build());
            assertThrows(NullPointerException.class, () -> HevcEncoderOptions.builder().chromaFormat(null));
            HevcEncoderOptions.builder().profile(HevcProfile.MAIN_444).chromaFormat(ChromaFormat.YUV444)
                    .color(ColorInfo.JPEG.withMatrix(MatrixCoefficients.IDENTITY)).build();
        }

        @Test
        @DisplayName("the codec registry codes the identity matrix as a 4:4:4 Main 4:4:4 stream")
        void registry() {
            var color = ColorInfo.JPEG.withMatrix(MatrixCoefficients.IDENTITY);
            var options = com.github.auties00.smalt.video.VideoEncoderOptions.builder().size(32, 16).color(color)
                    .frameRate(new com.github.auties00.smalt.track.Rational(30, 1)).bitrate(500_000).build();
            try (var encoder = com.github.auties00.smalt.video.VideoEncoders.create(
                    com.github.auties00.smalt.track.Codec.HEVC, options)) {
                var hevc = assertInstanceOf(HevcEncoder.class, encoder);
                assertEquals(HevcProfile.MAIN_444, hevc.options().profile());
                assertEquals(ChromaFormat.YUV444, hevc.options().chromaFormat());
            }
        }

        @Test
        @DisplayName("each profile names the chroma formats it codes")
        void chromaFormats() {
            for (var profile : HevcProfile.values()) {
                assertTrue(profile.supportsChromaFormat(ChromaFormat.YUV420), profile.toString());
                assertFalse(profile.supportsChromaFormat(ChromaFormat.MONOCHROME), profile.toString());
                assertFalse(profile.supportsChromaFormat(ChromaFormat.YUV440), profile.toString());
                var rext = profile.profileIdc() == 4;
                assertEquals(rext, profile.supportsChromaFormat(ChromaFormat.YUV422), profile.toString());
                assertEquals(rext && profile != HevcProfile.MAIN_422_10,
                        profile.supportsChromaFormat(ChromaFormat.YUV444), profile.toString());
            }
        }
    }

    /** Returns the 48 constraint bits of a configuration record, the first in the most significant bit. */
    private static long constraintBits(byte[] config) {
        var bits = 0L;
        for (var i = 6; i < 12; i++) {
            bits = (bits << 8) | (config[i] & 0xFF);
        }
        return bits;
    }

    private record Sets(HevcSps sps, HevcPps pps) {
    }

    /** Parses the sequence and picture parameter sets of an Annex B stream. */
    private static Sets parameterSets(byte[] annexB) {
        HevcSps sps = null;
        HevcPps pps = null;
        var spsTable = new HevcSps[16];
        for (var unit : units(annexB)) {
            var type = (unit[0] >> 1) & 0x3F;
            if (type != HevcNal.SPS && type != HevcNal.PPS) {
                continue;
            }
            var rbsp = NalUnits.unescape(unit, 2, unit.length - 2);
            var reader = new H264BitReader();
            reader.init(Arrays.copyOf(rbsp, rbsp.length + H264BitReader.PADDING), 0, rbsp.length);
            try {
                if (type == HevcNal.SPS) {
                    sps = HevcSps.parse(reader);
                    spsTable[sps.id] = sps;
                } else {
                    pps = HevcPps.parse(reader);
                    pps.activate(spsTable[pps.spsId]);
                }
            } catch (com.github.auties00.smalt.MediaException exception) {
                fail("the parameter set does not parse", exception);
            }
        }
        return new Sets(sps, pps);
    }

    /** Splits an Annex B stream into its NAL units. */
    private static List<byte[]> units(byte[] stream) {
        var units = new ArrayList<byte[]>();
        var start = -1;
        for (var i = 0; i + 3 <= stream.length; i++) {
            if (stream[i] == 0 && stream[i + 1] == 0 && stream[i + 2] == 1) {
                if (start >= 0) {
                    var end = i;
                    while (end > start && stream[end - 1] == 0) {
                        end--;
                    }
                    units.add(Arrays.copyOfRange(stream, start, end));
                }
                start = i + 3;
                i += 2;
            }
        }
        if (start >= 0) {
            units.add(Arrays.copyOfRange(stream, start, stream.length));
        }
        return units;
    }

    /** Returns the PSNR of the chroma planes of a decoded picture against its source, at the source's depth. */
    private static double chromaPsnr(Picture decoded, Picture source) {
        double sse = 0;
        long count = 0;
        int max;
        if (source instanceof YuvPicture s && decoded instanceof YuvPicture d) {
            max = 255;
            for (var y = 0; y < s.chromaHeight(); y++) {
                for (var x = 0; x < s.chromaWidth(); x++) {
                    var eu = (s.u()[y * s.uvStride() + x] & 0xFF) - (d.u()[y * d.uvStride() + x] & 0xFF);
                    var ev = (s.v()[y * s.uvStride() + x] & 0xFF) - (d.v()[y * d.uvStride() + x] & 0xFF);
                    sse += eu * eu + ev * ev;
                    count += 2;
                }
            }
        } else if (source instanceof Yuv16Picture s && decoded instanceof Yuv16Picture d) {
            max = (1 << s.bitDepth()) - 1;
            for (var y = 0; y < s.chromaHeight(); y++) {
                for (var x = 0; x < s.chromaWidth(); x++) {
                    var eu = (s.u()[y * s.uvStride() + x] & 0xFFFF) - (d.u()[y * d.uvStride() + x] & 0xFFFF);
                    var ev = (s.v()[y * s.uvStride() + x] & 0xFFFF) - (d.v()[y * d.uvStride() + x] & 0xFFFF);
                    sse += eu * eu + ev * ev;
                    count += 2;
                }
            }
        } else {
            throw new IllegalStateException("pictures of different kinds: " + source + " and " + decoded);
        }
        return 10 * Math.log10((double) max * max * count / Math.max(sse, 1e-9));
    }
}
