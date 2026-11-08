package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the DNG encoder's files with dng_validate, the validator of Adobe's DNG SDK, as a black box
 * (tools/testdata/dng-validate builds it): every file passes validation without a warning, the stage 1 image the SDK
 * reads from it (the stored raw samples, decoded by the SDK's own lossless JPEG decoder) equals the encoded image
 * sample for sample, and, for a DNG source, the SDK renders the converted file as it renders the source. The
 * decoder's floating-point rendering of DNG 1.7 high-dynamic-range camera profiles is compared with the SDK's.
 */
class DngValidateTest {
    static final Duration TIMEOUT = Duration.ofMinutes(5);

    /** The SDK renders a DNG file converted from a DNG source as it renders the source. */
    static final Tolerance SDK_RENDERING = Tolerance.psnr(40);

    /** The synthetic images written, one per image kind and option the encoder distinguishes. */
    enum Synthetic {
        BAYER_LOSSLESS(() -> DngEncoderTest.bayer(611, 409, CfaPattern.bayer("RGGB"), 1), DngEncoderOptions.defaults()),
        BAYER_UNCOMPRESSED(() -> DngEncoderTest.bayer(300, 201, CfaPattern.bayer("GBRG"), 2),
                DngEncoderOptions.builder().compression(DngCompression.UNCOMPRESSED).preview(false).build()),
        X_TRANS(() -> DngEncoderTest.bayer(450, 300, DngEncoderTest.xTrans(), 3),
                DngEncoderOptions.builder().tileSize(128).build()),
        FOUR_COLOR(() -> RawImage.ofCfa(256, 192, DngEncoderTest.scene(256, 192, 1, CfaPattern.bayer("CYGM"), 4),
                CfaPattern.bayer("CYGM"), new double[]{0.8, 1.0, 0.6, 0.9}, new double[]{
                        0.10, 0.45, 0.30, 0.12,
                        0.35, 0.40, 0.05, 0.20,
                        0.02, 0.05, 0.50, 0.25}), DngEncoderOptions.defaults()),
        LINEAR(() -> RawImage.ofLinear(320, 240, DngEncoderTest.scene(320, 240, 3, null, 5), DngEncoderTest.NEUTRAL,
                DngEncoderTest.SRGB_D50), DngEncoderOptions.defaults()),
        MONOCHROME(() -> RawImage.ofMonochrome(320, 240, DngEncoderTest.scene(320, 240, 1, null, 6)),
                DngEncoderOptions.defaults()),
        STAGGERED(() -> new RawImage(256, 256, 1, DngEncoderTest.scene(256, 256, 1, CfaPattern.bayer("RGGB"), 7),
                CfaPattern.bayer("RGGB"), DngEncoderTest.NEUTRAL, DngEncoderTest.SRGB_D50, CfaLayout.STAGGERED_A,
                List.of(CfaColor.RED, CfaColor.GREEN, CfaColor.BLUE)),
                DngEncoderOptions.builder().pixelAspectRatio(0.5).build());

        final Supplier<RawImage> image;
        final DngEncoderOptions options;

        Synthetic(Supplier<RawImage> image, DngEncoderOptions options) {
            this.image = image;
            this.options = options;
        }
    }

    /** The camera raw files converted. */
    enum Converted {
        PANASONIC_RW2(RawSamples.RW2_S5M2, false),
        CANON_SRAW(RawSamples.CR2_40D_SRAW, false),
        GOPRO_GPR(RawSamples.GPR_HERO7_BLACK_16BIT_4_3_2908, false),
        CANON_DNG(RawSamples.DNG_5D3_14BIT_SMALL, true),
        ADOBE_LINEAR_LOSSY_DNG(RawSamples.ADC_5D3_LINEAR_LOSSY, true),
        CANON_CR2(RawSamples.CR2_5D3, false),
        FUJIFILM_X_TRANS(RawSamples.RAF_XT2_COMPRESSED, false),
        FUJIFILM_SUPERCCD(RawSamples.RAF_S6500FD, false),
        PENTAX_MONOCHROME(RawSamples.PEF_K3III_MONOCHROME, false),
        PIXEL_DNG(RawSamples.DNG_PIXEL_2_XL, true),
        LEICA_DNG(RawSamples.DNG_LEICA_M9, true);

        final RawSamples sample;
        final boolean dng;

        Converted(RawSamples sample, boolean dng) {
            this.sample = sample;
            this.dng = dng;
        }
    }

    @Nested
    @DisplayName("converted DNG files of Adobe DNG Converter")
    class OracleConversions {
        @ParameterizedTest(name = "{0}")
        @EnumSource(DngConvertTest.OracleSample.class)
        @DisplayName("pass validation, hold the decoder's samples and render as their source does")
        void converted(DngConvertTest.OracleSample sample) {
            var source = sample.path();
            var file = save(sample.name() + ".dng", DngEncoder.convert(MediaSource.of(read(source))));
            assertValid(file, problems(validate(source)));
            var whole = RawDecoder.decodeRaw(MediaSource.of(read(file)), RawDecoderOptions.builder()
                    .defaultCrop(false).opcodes(false).build());
            assertArrayEquals(whole.samples(), stage1(file), "the SDK's stage 1 image");
            var expected = sdkRendering(source);
            var actual = sdkRendering(file);
            assertEquals(expected.width(), actual.width());
            assertEquals(expected.height(), actual.height());
            SDK_RENDERING.assertWithin(sample.name() + " SDK rendering", expected, actual);
        }
    }

    /**
     * The decoder renders a high-dynamic-range profile as the SDK does, compared on the encoded values of
     * {@code ProfileDynamicRange} (which map 0 to 16 times white onto 0 to 1) or, for a standard-range profile, on the
     * gamma-encoded values both write.
     */
    static final Tolerance HDR_RENDERING = Tolerance.psnr(40);

    /**
     * The profiles rendered by both: high-dynamic-range ones of each tag whose high-dynamic-range rule the SDK
     * applies as the specification states, and a standard-range one. The SDK departs from the specification for two
     * rules, which {@link DngHdrProfileTest} checks against the specification instead: it holds a tone curve whose
     * last input is below 1 at its last output rather than extending it by its slope, and it clips the value of a
     * table without value divisions to 1 under a high-dynamic-range profile too. It also applies a tone curve to the
     * largest and smallest channel and keeps the hue, where the decoder applies it to each channel, at every dynamic
     * range: the profiles with a tone curve other than the identity are compared on the neutral rows, where the two
     * agree, except for values past 16 times white, where the SDK's Linux build fails to render a neutral ramp
     * ("Index out of range"), so that profile's file holds none and is compared on the whole image.
     */
    enum HdrProfile {
        IDENTITY(file -> {
        }),
        TONE_CURVE(true, file -> file.toneCurve = new float[]{0, 0, 0.5f, 0.6f, 1, 0.9f}),
        TONE_CURVE_ABOVE_16(file -> {
            file.neutralRows = false;
            file.toneCurve = new float[]{0, 0, 0.25f, 0.3f, 0.5f, 0.6f, 0.8f, 0.82f, 1, 0.95f};
            file.baselineExposure = 5;
        }),
        TONE_CURVE_BRIGHT(true, file -> {
            file.toneCurve = new float[]{0, 0, 0.3f, 0.45f, 0.6f, 0.72f, 1, 1};
            file.baselineExposure = 3;
        }),
        HUE_SAT_MAP(file -> {
            file.hueSatDims = new int[]{6, 2, 2};
            file.hueSat = HdrProfileFiles.varied(6, 2, 2);
        }),
        LOOK_TABLE(file -> {
            file.lookDims = new int[]{6, 2, 3};
            file.look = HdrProfileFiles.varied(6, 2, 3);
        }),
        LOOK_TABLE_SRGB(file -> {
            file.lookDims = new int[]{6, 2, 3};
            file.look = HdrProfileFiles.varied(6, 2, 3);
            file.lookSrgb = true;
        }),
        HUE_SAT_MAP_SRGB(file -> {
            file.hueSatDims = new int[]{6, 2, 3};
            file.hueSat = HdrProfileFiles.varied(6, 2, 3);
            file.hueSatSrgb = true;
        }),
        STANDARD_LOOK_TABLE_SRGB(file -> {
            file.dynamicRange = -1;
            file.baselineExposure = 0;
            file.lookDims = new int[]{6, 2, 3};
            file.look = HdrProfileFiles.varied(6, 2, 3);
            file.lookSrgb = true;
        }),
        RGB_TABLES(file -> file.rgbTables = HdrProfileFiles.rgbTable(5, (r, g, b) -> new float[]{
                (float) Math.pow(r, 1.3), 0.8f * g + 0.1f * b, (float) Math.sqrt(b)})),
        COMBINED(file -> {
            file.hueSatDims = new int[]{6, 2, 2};
            file.hueSat = HdrProfileFiles.varied(6, 2, 2);
            file.lookDims = new int[]{6, 2, 3};
            file.look = HdrProfileFiles.varied(6, 2, 3);
            file.rgbTables = HdrProfileFiles.rgbTable(3, (r, g, b) -> new float[]{0.9f * r + 0.1f * g, g, b * b});
        }),
        COMBINED_NEUTRAL(true, file -> {
            file.toneCurve = new float[]{0, 0, 0.5f, 0.6f, 1, 0.9f};
            file.hueSatDims = new int[]{6, 2, 2};
            file.hueSat = HdrProfileFiles.varied(6, 2, 2);
            file.lookDims = new int[]{6, 2, 3};
            file.look = HdrProfileFiles.varied(6, 2, 3);
        }),
        STANDARD(file -> {
            file.dynamicRange = -1;
            file.baselineExposure = 0.5;
        });

        final boolean neutral;
        final java.util.function.Consumer<HdrProfileFiles> setup;

        HdrProfile(java.util.function.Consumer<HdrProfileFiles> setup) {
            this(false, setup);
        }

        HdrProfile(boolean neutral, java.util.function.Consumer<HdrProfileFiles> setup) {
            this.neutral = neutral;
            this.setup = setup;
        }

        HdrProfileFiles file() {
            var file = new HdrProfileFiles();
            setup.accept(file);
            return file;
        }
    }

    @Nested
    @DisplayName("high-dynamic-range camera profiles")
    class HdrProfiles {
        @ParameterizedTest(name = "{0}")
        @EnumSource(HdrProfile.class)
        @DisplayName("render in floating point as the SDK renders them, values above white included")
        void rendering(HdrProfile profile) {
            var file = profile.file();
            var dng = save("hdr-" + profile.name() + ".dng", file.write());
            assertValid(dng);
            var base = dng.resolveSibling(dng.getFileName() + "-float");
            var run = validate(dng, "-32", "-cs3", "-tif", base.toString());
            assertEquals(0, run.exitCode(), run.output());
            var sdk = UncompressedTiff.floats(read(base.resolveSibling(base.getFileName() + ".tif")));
            var smalt = (ChannelFloatPicture) RawDecoder.decode(MediaSource.of(read(dng)), RawDecoderOptions.builder()
                    .colorSpace(RawColorSpace.PROPHOTO_RGB).floatingPoint(true).build()).picture();
            var high = file.dynamicRange == 1;
            assertEquals(sdk.width(), smalt.width());
            assertEquals(sdk.height(), smalt.height());
            var first = profile.neutral ? HdrProfileFiles.NEUTRAL_ROWS : 0;
            var rows = sdk.height() - first;
            var expected = ChannelFloatPicture.allocate(sdk.width(), rows, PixelLayout.RGB);
            var actual = ChannelFloatPicture.allocate(sdk.width(), rows, PixelLayout.RGB);
            var brightest = 0.0;
            var offset = first * sdk.width() * 3;
            for (var j = 0; j < sdk.width() * rows * 3; j++) {
                var i = offset + j;
                var reference = sdk.pixels()[i];
                // the SDK writes a high-dynamic-range rendering in linear ProPhoto RGB, a standard one with its gamma
                var linear = Math.pow(smalt.pixels()[i], 1.8);
                brightest = Math.max(brightest, high ? linear : 0);
                expected.pixels()[j] = high ? (float) DngHdrProfileTest.encode(Math.max(reference, 0)) : reference;
                actual.pixels()[j] = high ? (float) DngHdrProfileTest.encode(linear) : smalt.pixels()[i];
            }
            HDR_RENDERING.assertWithin(profile.name() + " SDK rendering", expected, actual);
            if (high) {
                assertTrue(brightest > 1.5, "the rendering keeps values above white: " + brightest);
            }
        }
    }

    record Run(int exitCode, String output) {
    }

    static Run validate(Path file, String... options) {
        Oracle.assume("dng_validate");
        var arguments = new ArrayList<>(List.of(options));
        arguments.add(file.toString());
        var result = Oracle.execute(Oracle.find("dng_validate").orElseThrow(), TIMEOUT, null, arguments);
        return new Run(result.exitCode(), new String(result.stdout(), StandardCharsets.UTF_8)
                + result.stderr());
    }

    static void assertValid(Path file) {
        assertValid(file, List.of());
    }

    /** Checks that a file passes validation with no warning other than those its source has too. */
    static void assertValid(Path file, List<String> inherited) {
        var run = validate(file);
        assertEquals(0, run.exitCode(), run.output());
        assertTrue(run.output().contains("Validation complete"), run.output());
        var problems = new ArrayList<>(problems(run));
        problems.removeAll(inherited);
        assertEquals(List.of(), problems, run.output());
    }

    static List<String> problems(Run run) {
        return run.output().lines().filter(line -> line.contains("*** Warning") || line.contains("*** Error"))
                .toList();
    }

    static Path save(String name, byte[] data) {
        var file = Scratch.directory("dng-validate").resolve(name);
        try {
            Files.write(file, data);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return file;
    }

    static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Returns the stage 1 image the SDK reads from a DNG file: its stored samples, row by row. */
    static short[] stage1(Path dng) {
        var base = dng.resolveSibling(dng.getFileName() + "-stage1");
        var run = validate(dng, "-1", base.toString());
        assertEquals(0, run.exitCode(), run.output());
        return UncompressedTiff.samples(read(base.resolveSibling(base.getFileName() + ".tif")));
    }

    /** Returns the SDK's 8-bit sRGB rendering of a DNG file. */
    static RgbaPicture sdkRendering(Path dng) {
        var base = dng.resolveSibling(dng.getFileName() + "-render");
        var run = validate(dng, "-tif", base.toString());
        assertEquals(0, run.exitCode(), run.output());
        return UncompressedTiff.rgba(read(base.resolveSibling(base.getFileName() + ".tif")));
    }

    /** Returns the SDK's 8-bit sRGB rendering of a DNG file with one of its camera profiles. */
    static RgbaPicture sdkRendering(Path dng, String profile) {
        var base = dng.resolveSibling(dng.getFileName() + "-render-" + profile);
        var run = validate(dng, "-profile", profile, "-tif", base.toString());
        assertEquals(0, run.exitCode(), run.output());
        return UncompressedTiff.rgba(read(base.resolveSibling(base.getFileName() + ".tif")));
    }

    /** The DNG sources whose masks, profiles and private data a converted file carries over. */
    enum CarriedOver {
        MASKED_TABLE(List.of(), () -> {
            var builder = DngSpecGapsTest.linear(96, 64);
            builder.first.ascii(50708, "Smalt Spec").undefined(52543, maskedTable());
            DngCarryOverTest.rampMask(builder, "subject", 48, 32);
            return builder.write();
        }),
        BAYER_WHOLE_SAMPLE_MARGIN(List.of(), () -> {
            var builder = DngCarryOverTest.bayer(96, 64);
            builder.first.ascii(50708, "Smalt Spec").undefined(52543, maskedTable());
            DngCarryOverTest.rampMask(builder, "subject", 48, 32);
            return builder.write();
        }),
        BAYER_RESAMPLED_MASK(List.of(), () -> {
            var builder = DngCarryOverTest.bayer(90, 66);
            builder.first.ascii(50708, "Smalt Spec").undefined(52543, maskedTable());
            DngCarryOverTest.rampMask(builder, "subject", 30, 22);
            return builder.write();
        }),
        PROFILES(List.of("Primary", "Alternate", "Third"), () -> {
            var builder = DngCarryOverTest.Profiles.file("Alternate", false);
            builder.first.ascii(50708, "Smalt Spec");
            return builder.write();
        }),
        PRIVATE_DATA(List.of(), () -> {
            var builder = DngSpecGapsTest.linear(64, 48);
            var block = "Smalt\0private data".getBytes(StandardCharsets.US_ASCII);
            builder.first.ascii(50708, "Smalt Spec").raw(50740, 1, block.length, block);
            return builder.write();
        }),
        IPHONE_PRORAW(List.of(), () -> read(RawSamples.DNG_IPHONE_12_PRO.fetch()));

        final List<String> profiles;
        final Supplier<byte[]> source;

        /** A masked RGB table, little-endian as the SDK reads it in a little-endian file. */
        static byte[] maskedTable() {
            return DngCarryOverTest.Profiles.reorder(DngSpecGapsTest.constantTable("subject", 0.1f, 0));
        }

        CarriedOver(List<String> profiles, Supplier<byte[]> source) {
            this.profiles = profiles;
            this.source = source;
        }
    }

    @Nested
    @DisplayName("masks, camera profiles and private data carried over from a DNG source")
    class CarriedOverData {
        @ParameterizedTest(name = "{0}")
        @EnumSource(CarriedOver.class)
        @DisplayName("pass validation and render as their source does, with each camera profile")
        void carried(CarriedOver carried) {
            var source = save(carried.name() + "-source.dng", carried.source.get());
            var file = save(carried.name() + ".dng", DngEncoder.convert(MediaSource.of(read(source))));
            assertValid(file, problems(validate(source)));
            var expected = sdkRendering(source);
            var actual = sdkRendering(file);
            assertEquals(expected.width(), actual.width());
            assertEquals(expected.height(), actual.height());
            SDK_RENDERING.assertWithin(carried.name() + " SDK rendering", expected, actual);
            for (var profile : carried.profiles) {
                SDK_RENDERING.assertWithin(carried.name() + " SDK rendering with " + profile,
                        sdkRendering(source, profile), sdkRendering(file, profile));
            }
        }
    }

    @Nested
    @DisplayName("synthetic images")
    class Synthetics {
        @ParameterizedTest(name = "{0}")
        @EnumSource(Synthetic.class)
        @DisplayName("pass validation and the SDK reads back their samples")
        void synthetic(Synthetic synthetic) {
            var image = synthetic.image.get();
            var file = save(synthetic.name() + ".dng", DngEncoder.encode(image, synthetic.options));
            assertValid(file);
            var stored = RawDecoder.decodeRaw(MediaSource.of(read(file)), RawDecoderOptions.builder()
                    .defaultCrop(false).build());
            assertArrayEquals(stored.samples(), stage1(file), "the SDK's stage 1 image");
            assertArrayEquals(image.samples(), RawDecoder.decodeRaw(MediaSource.of(read(file))).samples());
        }

        @Test
        @DisplayName("the SDK checks the raw image digest: a changed sample fails validation")
        void digest() {
            var image = DngEncoderTest.bayer(300, 200, CfaPattern.bayer("RGGB"), 9);
            var data = DngEncoder.encode(image, DngEncoderOptions.builder().compression(DngCompression.UNCOMPRESSED)
                    .preview(false).build());
            var raw = DngStructure.of(data).raw();
            var offset = (int) raw.get(324).longs(ByteOrder.LITTLE_ENDIAN)[0];
            assertValid(save("digest-intact.dng", data));
            data[offset + 1000] ^= 0x10;
            var run = validate(save("digest-changed.dng", data));
            assertTrue(run.output().contains("NewRawImageDigest does not match raw image"), run.output());
        }
    }

    @Nested
    @DisplayName("converted camera raw files")
    class Conversions {
        @ParameterizedTest(name = "{0}")
        @EnumSource(Converted.class)
        @DisplayName("pass validation, hold the decoder's samples and render as their DNG source does")
        void converted(Converted converted) {
            var source = converted.sample.fetch();
            var file = save(converted.name() + ".dng", DngEncoder.convert(MediaSource.of(read(source))));
            assertValid(file);
            var whole = RawDecoder.decodeRaw(MediaSource.of(read(file)), RawDecoderOptions.builder()
                    .defaultCrop(false).opcodes(false).build());
            assertArrayEquals(whole.samples(), stage1(file), "the SDK's stage 1 image");
            if (converted.dng) {
                var expected = sdkRendering(source);
                var actual = sdkRendering(file);
                assertEquals(expected.width(), actual.width());
                assertEquals(expected.height(), actual.height());
                SDK_RENDERING.assertWithin(converted.name() + " SDK rendering", expected, actual);
            }
        }
    }

    /**
     * Reads the uncompressed TIFF files dng_validate writes: 8- or 16-bit samples in strips or tiles, chunky, either
     * byte order.
     */
    static final class UncompressedTiff {
        static short[] samples(byte[] tiff) {
            var structure = DngStructure.of(tiff);
            var ifd = structure.first();
            var order = structure.order;
            var width = (int) ifd.get(256).longs(order)[0];
            var height = (int) ifd.get(257).longs(order)[0];
            var spp = ifd.has(277) ? (int) ifd.get(277).longs(order)[0] : 1;
            var bits = (int) ifd.get(258).longs(order)[0];
            assertEquals(1, ifd.has(259) ? ifd.get(259).longs(order)[0] : 1, "uncompressed");
            var out = new short[width * height * spp];
            var buffer = ByteBuffer.wrap(tiff).order(order);
            int chunkWidth;
            int chunkHeight;
            long[] offsets;
            if (ifd.has(322)) {
                chunkWidth = (int) ifd.get(322).longs(order)[0];
                chunkHeight = (int) ifd.get(323).longs(order)[0];
                offsets = ifd.get(324).longs(order);
            } else {
                chunkWidth = width;
                chunkHeight = ifd.has(278) ? (int) Math.min(height, ifd.get(278).longs(order)[0]) : height;
                offsets = ifd.get(273).longs(order);
            }
            var across = Math.ceilDiv(width, chunkWidth);
            for (var chunk = 0; chunk < offsets.length; chunk++) {
                var left = (chunk % across) * chunkWidth;
                var top = (chunk / across) * chunkHeight;
                for (var row = 0; row < chunkHeight && top + row < height; row++) {
                    for (var column = 0; column < chunkWidth; column++) {
                        if (left + column >= width) {
                            continue;
                        }
                        for (var s = 0; s < spp; s++) {
                            var index = (row * chunkWidth + column) * spp + s;
                            var value = bits == 16 ? buffer.getShort((int) offsets[chunk] + 2 * index)
                                    : (short) (buffer.get((int) offsets[chunk] + index) & 0xFF);
                            out[((top + row) * width + left + column) * spp + s] = value;
                        }
                    }
                }
            }
            return out;
        }

        /** Reads the 32-bit floating-point chunky RGB image dng_validate writes with {@code -32}. */
        static ChannelFloatPicture floats(byte[] tiff) {
            var structure = DngStructure.of(tiff);
            var ifd = structure.first();
            var order = structure.order;
            var width = (int) ifd.get(256).longs(order)[0];
            var height = (int) ifd.get(257).longs(order)[0];
            var spp = (int) ifd.get(277).longs(order)[0];
            assertEquals(32, ifd.get(258).longs(order)[0], "bits per sample");
            assertEquals(3, ifd.get(339).longs(order)[0], "floating-point samples");
            assertEquals(1, ifd.has(259) ? ifd.get(259).longs(order)[0] : 1, "uncompressed");
            var picture = ChannelFloatPicture.allocate(width, height, PixelLayout.RGB);
            var buffer = ByteBuffer.wrap(tiff).order(order);
            int chunkWidth;
            int chunkHeight;
            long[] offsets;
            if (ifd.has(322)) {
                chunkWidth = (int) ifd.get(322).longs(order)[0];
                chunkHeight = (int) ifd.get(323).longs(order)[0];
                offsets = ifd.get(324).longs(order);
            } else {
                chunkWidth = width;
                chunkHeight = ifd.has(278) ? (int) Math.min(height, ifd.get(278).longs(order)[0]) : height;
                offsets = ifd.get(273).longs(order);
            }
            var across = Math.ceilDiv(width, chunkWidth);
            for (var chunk = 0; chunk < offsets.length; chunk++) {
                var left = (chunk % across) * chunkWidth;
                var top = (chunk / across) * chunkHeight;
                for (var row = 0; row < chunkHeight && top + row < height; row++) {
                    for (var column = 0; column < chunkWidth && left + column < width; column++) {
                        for (var s = 0; s < 3; s++) {
                            var index = (row * chunkWidth + column) * spp + Math.min(s, spp - 1);
                            picture.pixels()[((top + row) * width + left + column) * 3 + s] =
                                    buffer.getFloat((int) offsets[chunk] + 4 * index);
                        }
                    }
                }
            }
            return picture;
        }

        static RgbaPicture rgba(byte[] tiff) {
            var structure = DngStructure.of(tiff);
            var ifd = structure.first();
            var order = structure.order;
            var width = (int) ifd.get(256).longs(order)[0];
            var height = (int) ifd.get(257).longs(order)[0];
            var spp = (int) ifd.get(277).longs(order)[0];
            var samples = samples(tiff);
            var picture = RgbaPicture.allocate(width, height, false);
            var pixels = picture.pixels();
            for (var i = 0; i < width * height; i++) {
                for (var c = 0; c < 3; c++) {
                    pixels[i * 4 + c] = (byte) samples[i * spp + Math.min(c, spp - 1)];
                }
                pixels[i * 4 + 3] = (byte) 0xFF;
            }
            return ImageOps.toRgba(picture);
        }
    }
}
