package com.github.auties00.smalt.image.raw;

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
 * sample for sample, and, for a DNG source, the SDK renders the converted file as it renders the source.
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
