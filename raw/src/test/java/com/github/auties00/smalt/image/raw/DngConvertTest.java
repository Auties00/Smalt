package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Converts camera raw samples of every family the decoder reads (Bayer, X-Trans, four-color, staggered,
 * two-sample, monochrome, linear and DNG sources) into DNG files and checks them against the source through the
 * decoder: the photosites of the default crop and of the whole active area are identical, the color description
 * and metadata are kept, and the rendering of the DNG file matches the source's.
 */
class DngConvertTest {
    /** The samples converted, one per sensor family; those of the smoke tier first. */
    enum Sample {
        PANASONIC_RW2_SMOKE(RawSamples.RW2_S5M2),
        CANON_SRAW_SMOKE(RawSamples.CR2_40D_SRAW),
        CANON_DNG_SMOKE(RawSamples.DNG_5D3_14BIT_SMALL),
        GOPRO_GPR_SMOKE(RawSamples.GPR_HERO7_BLACK_16BIT_4_3_2908),
        ADOBE_LINEAR_LOSSY_DNG_SMOKE(RawSamples.ADC_5D3_LINEAR_LOSSY),
        CANON_CR2(RawSamples.CR2_5D3),
        CANON_CMYG(RawSamples.CRW_G1),
        NIKON_CMYG(RawSamples.NEF_E5700),
        NIKON_NEF(RawSamples.NEF_D7000_LOSSLESS_14),
        SONY_ARW(RawSamples.ARW_A7M2_UNCOMPRESSED_14),
        FUJIFILM_X_TRANS(RawSamples.RAF_XT2_COMPRESSED),
        OLYMPUS_ORF(RawSamples.ORF_EM1),
        PANASONIC_RW2(RawSamples.RW2_G9),
        PENTAX_PEF(RawSamples.PEF_K5),
        PENTAX_MONOCHROME(RawSamples.PEF_K3III_MONOCHROME),
        FUJIFILM_SUPERCCD(RawSamples.RAF_S6500FD),
        FUJIFILM_SUPERCCD_SR(RawSamples.RAF_S5PRO),
        CANON_SRAW(RawSamples.CR2_5D3_SRAW),
        SIGMA_X3F(RawSamples.X3F_DP1),
        LEICA_DNG(RawSamples.DNG_LEICA_M9),
        PIXEL_DNG(RawSamples.DNG_PIXEL_2_XL),
        IPHONE_DNG(RawSamples.DNG_IPHONE_12_PRO),
        ADOBE_LINEAR_DNG(RawSamples.ADC_5D3_LINEAR_LOSSLESS);

        final RawSamples sample;

        Sample(RawSamples sample) {
            this.sample = sample;
        }
    }

    /** The rendering of a DNG file differs from its source's only by the 16-bit quantization of its samples. */
    static final Tolerance RENDERING = Tolerance.psnr(45);

    static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * The DNG files Adobe DNG Converter wrote for samples whose sensors the vendor files alone do not describe
     * (tools/testdata/raw-oracle.py): four-color filter arrays with the converter's color matrices, staggered and
     * two-sample SuperCCD images, X-Trans, and JPEG XL tiles.
     */
    enum OracleSample {
        CANON_CMYG("rawpixls-canon-powershot-g1-raw-4-3.dng"),
        CANON_CMYG_JPEG_XL("rawpixls-canon-powershot-g1-raw-4-3-jxl.dng"),
        NIKON_CMYG("rawpixls-nikon-e5700-12bit-12bit-uncompressed-4-3.dng"),
        SONY_RGBE("rawpixls-sony-dsc-f828-4-3.dng"),
        FUJIFILM_SUPERCCD("rawpixls-fujifilm-finepix-s6500fd-4-3.dng"),
        FUJIFILM_SUPERCCD_SR("rawpixls-fujifilm-finepix-s5pro-3-2.dng"),
        FUJIFILM_X_TRANS("rawpixls-fujifilm-x-t2-14bit-14bit-compressed-3-2.dng");

        final String file;

        OracleSample(String file) {
            this.file = file;
        }

        Path path() {
            var path = Path.of(System.getProperty("smalt.test.rawOracle",
                    Conformance.directory().resolve("raw-oracle").toString())).resolve(file);
            Skip.unless(Files.isRegularFile(path), "data:raw-oracle", "no oracle file " + path
                    + "; run tools/testdata/raw-oracle.py");
            return path;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Sample.class)
    @DisplayName("a converted raw file keeps its photosites, colors, metadata and rendering")
    void convert(Sample sample) {
        check(sample.name(), read(sample.sample.fetch()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OracleSample.class)
    @DisplayName("a converted DNG file of Adobe DNG Converter keeps its photosites, colors, metadata and rendering")
    void convertOracle(OracleSample sample) {
        check(sample.name(), read(sample.path()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RawSamples.class, names = {"CRW_G1", "NEF_E5700"})
    @DisplayName("a four-color file without color matrices is converted with its fitted matrix as ColorMatrix1 under D65")
    void fourColorMatrix(RawSamples sample) {
        var source = read(sample.fetch());
        var dng = DngEncoder.convert(MediaSource.of(source));
        var info = RawDecoder.info(MediaSource.of(dng));
        assertTrue(info.isColorCalibrated());
        var original = RawDecoder.decodeRaw(MediaSource.of(source));
        var converted = RawDecoder.decodeRaw(MediaSource.of(dng));
        assertEquals(12, original.balancedToXyz().length);
        assertArrayEquals(original.cameraNeutral(), converted.cameraNeutral(), 1e-6);
        assertArrayEquals(original.balancedToXyz(), converted.balancedToXyz(), 1e-6);
    }

    @Test
    @DisplayName("the decoding options' white balance, color matrix and crop choice shape the file")
    void decodingOptions() {
        var source = read(RawSamples.DNG_5D3_14BIT_SMALL.fetch());
        // an arbitrary well-conditioned XYZ to camera matrix, which replaces the file's description
        double[] matrix = {0.70, -0.10, -0.08, -0.40, 1.20, 0.22, -0.10, 0.20, 0.60};
        var choices = List.of(
                RawDecoderOptions.builder().whiteBalance(RawWhiteBalance.temperature(4500)).build(),
                RawDecoderOptions.builder().colorMatrix(matrix).build(),
                RawDecoderOptions.builder().whiteBalance(RawWhiteBalance.temperature(6500)).colorMatrix(matrix)
                        .build());
        for (var decoding : choices) {
            var dng = DngEncoder.convert(MediaSource.of(source), decoding, DngEncoderOptions.defaults());
            assertSame(RawDecoder.decodeRaw(MediaSource.of(source), decoding), RawDecoder.decodeRaw(
                    MediaSource.of(dng)), 1e-6);
        }
        var whole = RawDecoderOptions.builder().defaultCrop(false).build();
        var dng = DngEncoder.convert(MediaSource.of(source), whole, DngEncoderOptions.builder().preview(false)
                .build());
        assertSame(RawDecoder.decodeRaw(MediaSource.of(source), whole), RawDecoder.decodeRaw(MediaSource.of(dng)),
                1e-6);
    }

    @Test
    @DisplayName("a truncated, damaged or foreign input fails with a media exception")
    void malformed() {
        assertThrows(MediaException.class, () -> DngEncoder.convert(MediaSource.of(new byte[0])));
        assertThrows(MediaException.class, () -> DngEncoder.convert(MediaSource.of(new byte[4096])));
        var dng = DngEncoder.convert(MediaSource.of(read(RawSamples.DNG_5D3_14BIT_SMALL.fetch())));
        for (var source : List.of(read(RawSamples.RW2_S5M2.fetch()), dng)) {
            for (var length : new int[]{8, 64, 300, source.length / 3, source.length / 2, source.length - 1}) {
                var truncated = Arrays.copyOf(source, length);
                try {
                    DngEncoder.convert(MediaSource.of(truncated));
                } catch (MediaException expected) {
                    // a truncated file may still hold a whole image, so only other failures are wrong
                }
            }
            var random = new SplittableRandom(source.length);
            for (var round = 0; round < 8; round++) {
                var damaged = source.clone();
                for (var flip = 0; flip < 16; flip++) {
                    damaged[random.nextInt(Math.min(damaged.length, 65536))] ^= (byte) (1 + random.nextInt(255));
                }
                try {
                    DngEncoder.convert(MediaSource.of(damaged));
                } catch (MediaException expected) {
                    // damaged headers and sensor data are rejected or decoded as they are
                }
            }
        }
    }

    static void check(String name, byte[] source) {
        var dng = DngEncoder.convert(MediaSource.of(source));

        var original = RawDecoder.decodeRaw(MediaSource.of(source));
        var converted = RawDecoder.decodeRaw(MediaSource.of(dng));
        assertSame(original, converted, 1e-6);

        var whole = RawDecoderOptions.builder().defaultCrop(false).build();
        assertContains(RawDecoder.decodeRaw(MediaSource.of(source), whole), RawDecoder.decodeRaw(MediaSource.of(dng),
                whole));

        var sourceInfo = RawDecoder.info(MediaSource.of(source));
        var info = RawDecoder.info(MediaSource.of(dng));
        assertEquals(RawFormat.DNG, info.format());
        assertEquals(sourceInfo.make(), info.make());
        assertEquals(sourceInfo.orientation(), info.orientation());
        assertEquals(sourceInfo.width(), info.width());
        assertEquals(sourceInfo.height(), info.height());
        assertEquals(sourceInfo.iso(), info.iso());
        // a vendor exposure time or f-number computed from an APEX value is written as a rational, to nine decimals
        assertClose(sourceInfo.exposureTime(), info.exposureTime());
        assertClose(sourceInfo.fNumber(), info.fNumber());
        assertTrue(info.previews().stream().anyMatch(preview -> preview.format() == ImageFormat.JPEG
                && Math.max(preview.width(), preview.height()) == Math.min(1024, Math.max(info.width(),
                info.height()))), info.previews().toString());

        var rendering = RawDecoderOptions.fast().toBuilder().embeddedPreview(false).build();
        var expected = ImageOps.toRgba(RawDecoder.decode(MediaSource.of(source), rendering).picture());
        var actual = ImageOps.toRgba(RawDecoder.decode(MediaSource.of(dng), rendering).picture());
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        RENDERING.assertWithin(name + " rendering", expected, actual);
    }

    static void assertClose(java.util.OptionalDouble expected, java.util.OptionalDouble actual) {
        assertEquals(expected.isPresent(), actual.isPresent(), expected + " against " + actual);
        if (expected.isPresent()) {
            assertEquals(expected.getAsDouble(), actual.getAsDouble(), 1e-9);
        }
    }

    /**
     * Checks that the whole stored image of a DNG file holds the whole active area of its source: the same image,
     * or the same image inside the margin the encoder adds around a default crop too close to the edges.
     */
    static void assertContains(RawImage source, RawImage dng) {
        if (dng.width() == source.width() && dng.height() == source.height()) {
            assertSame(source, dng, 1e-6);
            return;
        }
        var spp = source.channels();
        var marginX = dng.width() - source.width();
        var marginY = dng.height() - source.height();
        for (var left : new int[]{0, marginX / 2, marginX}) {
            for (var top : new int[]{0, marginY / 2, marginY}) {
                var matches = true;
                for (var y = 0; y < source.height() && matches; y++) {
                    for (var x = 0; x < source.width() * spp && matches; x++) {
                        matches = source.samples()[y * source.width() * spp + x]
                                == dng.samples()[((y + top) * dng.width() + left) * spp + x];
                    }
                }
                if (matches) {
                    return;
                }
            }
        }
        throw new AssertionError("the stored image of " + dng.width() + "x" + dng.height()
                + " does not hold the active area of " + source.width() + "x" + source.height());
    }

    static void assertSame(RawImage expected, RawImage actual, double matrixTolerance) {
        assertEquals(expected.width(), actual.width(), "width");
        assertEquals(expected.height(), actual.height(), "height");
        assertEquals(expected.channels(), actual.channels(), "channels");
        assertEquals(expected.cfaPattern(), actual.cfaPattern(), "pattern");
        assertEquals(expected.cfaLayout(), actual.cfaLayout(), "layout");
        assertEquals(expected.colorPlanes(), actual.colorPlanes(), "planes");
        assertArrayEquals(expected.samples(), actual.samples(), "samples");
        assertArrayEquals(expected.cameraNeutral(), actual.cameraNeutral(), 1e-8, "neutral");
        assertArrayEquals(expected.balancedToXyz(), actual.balancedToXyz(), matrixTolerance, "matrix");
    }
}
