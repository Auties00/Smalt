package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.ColorSpace;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.QuantizationTable;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.Sampling;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.Scan;
import com.github.auties00.smalt.image.tiff.TiffEncoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the coding-mode settings of {@link JpegEncoderOptions}: their defaults, the builder round trip, the
 * checks of {@code build()} and of the value records, the checks that need the picture, and the headers each
 * color space writes.
 */
@DisplayName("JpegEncoderOptions coding modes")
class JpegEncoderModeOptionsTest {
    private static RgbaPicture picture(int width, int height) {
        var picture = RgbaPicture.allocate(width, height, false);
        for (var i = 0; i < picture.pixels().length; i++) {
            picture.pixels()[i] = (byte) (i * 7);
        }
        return picture;
    }

    @Test
    @DisplayName("default to a baseline 8-bit YCbCr file with the Annex K tables and a 1:1 aspect ratio")
    void defaults() {
        var defaults = JpegEncoderOptions.defaults();
        assertEquals(ColorSpace.YCBCR, defaults.colorSpace());
        assertFalse(defaults.progressive());
        assertFalse(defaults.arithmetic());
        assertFalse(defaults.lossless());
        assertEquals(1, defaults.predictor());
        assertEquals(0, defaults.pointTransform());
        assertTrue(defaults.precision().isEmpty());
        assertTrue(defaults.chromaQuality().isEmpty());
        assertTrue(defaults.samplingFactors().isEmpty());
        assertTrue(defaults.quantizationTables().isEmpty());
        assertTrue(defaults.quantizationSlots().isEmpty());
        assertTrue(defaults.baselineQuantization());
        assertTrue(defaults.scanScript().isEmpty());
        assertEquals(PixelDensity.ofAspectRatio(1, 1), defaults.density());
        assertTrue(JpegEncoderOptions.ofProgressive(60).progressive());
        assertEquals(60, JpegEncoderOptions.ofProgressive(60).quality());
        assertTrue(JpegEncoderOptions.ofLossless().lossless());
        assertEquals(ColorSpace.RGB, JpegEncoderOptions.ofLossless().colorSpace());
    }

    @Test
    @DisplayName("round-trip every setting through toBuilder() with value equality, and reset to automatic")
    void roundTrip() {
        var tables = List.of(QuantizationTable.ANNEX_K_LUMINANCE, QuantizationTable.ANNEX_K_CHROMINANCE,
                QuantizationTable.of(Collections.nCopies(64, 9).stream().mapToInt(Integer::intValue).toArray()));
        var built = JpegEncoderOptions.builder().quality(80).chromaQuality(55).colorSpace(ColorSpace.YCCK)
                .progressive(true).arithmetic(true).precision(12).samplingFactors(Sampling.of(1, 2))
                .quantizationTables(tables).quantizationSlots(0, 2).baselineQuantization(false)
                .density(PixelDensity.ofDotsPerInch(300, 200))
                .scanScript(Scan.of(0, 0, 0, 0, 0, 1, 2, 3), Scan.of(1, 63, 0, 0, 0), Scan.of(1, 63, 0, 0, 1),
                        Scan.of(1, 63, 0, 0, 2), Scan.of(1, 63, 0, 0, 3))
                .build();
        assertEquals(built, built.toBuilder().build());
        assertEquals(built.hashCode(), built.toBuilder().build().hashCode());
        assertEquals(55, built.chromaQuality().getAsInt());
        assertEquals(12, built.precision().getAsInt());
        assertEquals(List.of(Sampling.of(1, 2)), built.samplingFactors());
        assertEquals(tables, built.quantizationTables());
        assertEquals(List.of(0, 2), built.quantizationSlots());
        assertEquals(5, built.scanScript().size());
        assertTrue(built.toString().contains("YCCK"), built.toString());
        var reset = built.toBuilder().automaticChromaQuality().automaticPrecision().automaticSamplingFactors()
                .automaticQuantizationTables().automaticQuantizationSlots().automaticScanScript().progressive(false)
                .arithmetic(false).colorSpace(ColorSpace.YCBCR).baselineQuantization(true)
                .density(PixelDensity.ofAspectRatio(1, 1)).quality(75).build();
        assertEquals(JpegEncoderOptions.defaults(), reset);
        assertNotEquals(built, reset);
    }

    static Stream<Arguments> invalidBuilds() {
        return Stream.of(
                Arguments.of("lossless and progressive", (Executable) () -> JpegEncoderOptions.builder().lossless(true)
                        .progressive(true).build()),
                Arguments.of("10-bit DCT", (Executable) () -> JpegEncoderOptions.builder().precision(10).build()),
                Arguments.of("1-bit lossless", (Executable) () -> JpegEncoderOptions.builder().lossless(true).precision(1)
                        .build()),
                Arguments.of("17-bit lossless", (Executable) () -> JpegEncoderOptions.builder().lossless(true)
                        .precision(17).build()),
                Arguments.of("point transform at the precision", (Executable) () -> JpegEncoderOptions.builder()
                        .lossless(true).precision(4).pointTransform(4).build()),
                Arguments.of("predictor 0", (Executable) () -> JpegEncoderOptions.builder().predictor(0).build()),
                Arguments.of("predictor 8", (Executable) () -> JpegEncoderOptions.builder().predictor(8).build()),
                Arguments.of("point transform 16", (Executable) () -> JpegEncoderOptions.builder().pointTransform(16)
                        .build()),
                Arguments.of("chroma quality 101", (Executable) () -> JpegEncoderOptions.builder().chromaQuality(101)
                        .build()),
                Arguments.of("monochrome RGB", (Executable) () -> JpegEncoderOptions.builder().colorSpace(ColorSpace.RGB)
                        .chromaFormat(ChromaFormat.MONOCHROME).build()),
                Arguments.of("monochrome YCCK", (Executable) () -> JpegEncoderOptions.builder()
                        .colorSpace(ColorSpace.YCCK).chromaFormat(ChromaFormat.MONOCHROME).build()),
                Arguments.of("two factors for gray", (Executable) () -> JpegEncoderOptions.builder()
                        .chromaFormat(ChromaFormat.MONOCHROME).samplingFactors(Sampling.of(1, 1), Sampling.of(1, 1))
                        .build()),
                Arguments.of("five tables", (Executable) () -> JpegEncoderOptions.builder()
                        .quantizationTables(Collections.nCopies(5, QuantizationTable.ANNEX_K_LUMINANCE)).build()),
                Arguments.of("slot 2 without a table", (Executable) () -> JpegEncoderOptions.builder()
                        .quantizationSlots(0, 2).build()),
                Arguments.of("slot 4", (Executable) () -> JpegEncoderOptions.builder().quantizationSlots(4).build()),
                Arguments.of("negative slot", (Executable) () -> JpegEncoderOptions.builder().quantizationSlots(-1)
                        .build()),
                Arguments.of("sequential component twice", (Executable) () -> JpegEncoderOptions.builder()
                        .scanScript(Scan.of(0, 63, 0, 0, 0, 1), Scan.of(0, 63, 0, 0, 1, 2)).build()),
                Arguments.of("sequential component missing", (Executable) () -> JpegEncoderOptions.builder()
                        .scanScript(Scan.of(0, 63, 0, 0, 0, 1)).build()),
                Arguments.of("sequential partial band", (Executable) () -> JpegEncoderOptions.builder()
                        .scanScript(Scan.of(0, 62, 0, 0, 0, 1, 2)).build()),
                Arguments.of("scan component out of range", (Executable) () -> JpegEncoderOptions.builder()
                        .scanScript(Scan.of(0, 63, 0, 0, 0, 1, 3)).build()),
                Arguments.of("progressive AC before DC", (Executable) () -> JpegEncoderOptions.builder().progressive(true)
                        .scanScript(Scan.of(1, 63, 0, 0, 0), Scan.of(0, 0, 0, 0, 0, 1, 2)).build()),
                Arguments.of("progressive AC of two components", (Executable) () -> JpegEncoderOptions.builder()
                        .progressive(true).scanScript(Scan.of(0, 0, 0, 0, 0, 1, 2), Scan.of(1, 63, 0, 0, 0, 1))
                        .build()),
                Arguments.of("progressive DC and AC together", (Executable) () -> JpegEncoderOptions.builder()
                        .progressive(true).scanScript(Scan.of(0, 5, 0, 0, 0, 1, 2)).build()),
                Arguments.of("progressive refinement out of order", (Executable) () -> JpegEncoderOptions.builder()
                        .progressive(true).scanScript(Scan.of(0, 0, 0, 2, 0, 1, 2), Scan.of(0, 0, 1, 0, 0, 1, 2))
                        .build()),
                Arguments.of("progressive first scan with a high bit", (Executable) () -> JpegEncoderOptions.builder()
                        .progressive(true).scanScript(Scan.of(0, 0, 1, 0, 0, 1, 2)).build()),
                Arguments.of("progressive band reversed", (Executable) () -> JpegEncoderOptions.builder()
                        .progressive(true).scanScript(Scan.of(0, 0, 0, 0, 0, 1, 2), Scan.of(9, 3, 0, 0, 0)).build()),
                Arguments.of("progressive without DC of a component", (Executable) () -> JpegEncoderOptions.builder()
                        .progressive(true).scanScript(Scan.of(0, 0, 0, 0, 0, 1)).build()),
                Arguments.of("progressive bit 11 at 8 bits", (Executable) () -> JpegEncoderOptions.builder()
                        .precision(8).progressive(true).scanScript(Scan.of(0, 0, 0, 11, 0, 1, 2)).build()),
                Arguments.of("lossless scan with a band", (Executable) () -> JpegEncoderOptions.builder().lossless(true)
                        .scanScript(Scan.of(1, 1, 0, 0, 0, 1, 2)).build()),
                Arguments.of("lossless predictor 0", (Executable) () -> JpegEncoderOptions.builder().lossless(true)
                        .scanScript(Scan.of(0, 0, 0, 0, 0, 1, 2)).build()),
                Arguments.of("lossless transform at the precision", (Executable) () -> JpegEncoderOptions.builder()
                        .lossless(true).precision(6).scanScript(Scan.of(1, 0, 0, 6, 0, 1, 2)).build()),
                Arguments.of("lossless component twice", (Executable) () -> JpegEncoderOptions.builder().lossless(true)
                        .scanScript(Scan.of(1, 0, 0, 0, 0, 1, 2), Scan.of(2, 0, 0, 0, 2)).build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBuilds")
    @DisplayName("build() rejects settings that cannot make a file")
    void rejects(String name, Executable build) {
        assertThrows(IllegalArgumentException.class, build, name);
    }

    @Test
    @DisplayName("the value records check their ranges")
    void records() {
        assertThrows(IllegalArgumentException.class, () -> Sampling.of(0, 1));
        assertThrows(IllegalArgumentException.class, () -> Sampling.of(1, 5));
        assertThrows(IllegalArgumentException.class, () -> QuantizationTable.of(new int[63]));
        var zero = new int[64];
        zero[5] = 1;
        assertThrows(IllegalArgumentException.class, () -> QuantizationTable.of(zero));
        var big = new int[64];
        java.util.Arrays.fill(big, 32768);
        assertThrows(IllegalArgumentException.class, () -> QuantizationTable.of(big));
        assertThrows(IllegalArgumentException.class, () -> Scan.of(0, 63, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Scan.of(0, 63, 0, 0, 0, 1, 2, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> Scan.of(0, 63, 0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> Scan.of(0, 64, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Scan.of(0, 63, 16, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> JpegEncoderOptions.builder()
                .density(PixelDensity.ofDotsPerInch(0.4, 72)).build());
        assertThrows(IllegalArgumentException.class, () -> JpegEncoderOptions.builder()
                .density(PixelDensity.ofDotsPerCentimeter(72, 65535.5)).build());
        assertThrows(IllegalArgumentException.class, () -> JpegEncoderOptions.builder()
                .density(PixelDensity.ofAspectRatio(70000, 1)).build());
        assertThrows(NullPointerException.class, () -> JpegEncoderOptions.builder().density(null));
        var ones = new int[64];
        java.util.Arrays.fill(ones, 1);
        assertArrayEquals(ones, QuantizationTable.of(ones).toArray());
        assertEquals(16, QuantizationTable.ANNEX_K_LUMINANCE.values().getFirst());
        assertEquals(99, QuantizationTable.ANNEX_K_CHROMINANCE.values().getLast());
    }

    @Nested
    @DisplayName("settings checked against the picture")
    class AtEncode {
        @Test
        @DisplayName("sampling factors must divide the largest ones")
        void fractionalSampling() {
            var options = JpegEncoderOptions.builder().samplingFactors(Sampling.of(3, 1), Sampling.of(2, 1)).build();
            assertThrows(IllegalArgumentException.class, () -> JpegEncoder.encode(picture(16, 16), options));
        }

        @Test
        @DisplayName("an interleaved MCU holds at most 10 blocks")
        void mcuSize() {
            var options = JpegEncoderOptions.builder().samplingFactors(Sampling.of(4, 2), Sampling.of(2, 1),
                    Sampling.of(1, 1)).build();
            assertThrows(IllegalArgumentException.class, () -> JpegEncoder.encode(picture(16, 16), options));
            var separate = options.toBuilder().scanScript(Scan.of(0, 63, 0, 0, 0), Scan.of(0, 63, 0, 0, 1),
                    Scan.of(0, 63, 0, 0, 2)).build();
            assertTrue(JpegEncoder.encode(picture(16, 16), separate).length > 0);
        }

        @Test
        @DisplayName("a lossless restart interval must be a whole number of MCU rows")
        void losslessRestart() {
            var picture = picture(10, 6);
            var options = JpegEncoderOptions.ofLossless().toBuilder().restartInterval(15).build();
            assertThrows(IllegalArgumentException.class, () -> JpegEncoder.encode(picture, options));
            assertTrue(JpegEncoder.encode(picture, options.toBuilder().restartInterval(20).build()).length > 0);
        }

        @Test
        @DisplayName("the point transform must be below the automatic precision")
        void automaticPrecision() {
            var options = JpegEncoderOptions.builder().lossless(true).pointTransform(8).build();
            assertThrows(IllegalArgumentException.class, () -> JpegEncoder.encode(picture(8, 8), options));
            assertTrue(JpegEncoder.encode(picture(8, 8), options.toBuilder().pointTransform(7).build()).length > 0);
        }

        @Test
        @DisplayName("a progressive bit position beyond 10 needs 12 bits")
        void bitPosition() {
            var script = JpegEncoderOptions.builder().progressive(true).scanScript(Scan.of(0, 0, 0, 12, 0, 1, 2),
                    Scan.of(0, 0, 12, 11, 0, 1, 2)).build();
            assertThrows(IllegalArgumentException.class, () -> JpegEncoder.encode(picture(8, 8), script));
            assertTrue(JpegEncoder.encode(picture(8, 8), script.toBuilder().precision(12).build()).length > 0);
        }
    }

    @Nested
    @DisplayName("file headers")
    class Headers {
        private static int indexOf(byte[] data, int marker) {
            for (var i = 2; i + 1 < data.length; i++) {
                if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == marker) {
                    return i;
                }
            }
            return -1;
        }

        @Test
        @DisplayName("YCbCr carries a JFIF segment with the density, the other color spaces an Adobe segment")
        void headers() {
            var picture = picture(20, 12);
            var dpi = JpegEncoder.encode(picture, JpegEncoderOptions.builder().density(PixelDensity.ofDotsPerInch(300, 150))
                    .build());
            var app0 = indexOf(dpi, 0xE0);
            assertEquals(2, app0);
            assertArrayEquals(new byte[]{'J', 'F', 'I', 'F', 0, 1, 1, 1, 0x01, 0x2C, 0x00, (byte) 0x96, 0, 0},
                    java.util.Arrays.copyOfRange(dpi, app0 + 4, app0 + 18));
            var cm = JpegEncoder.encode(picture, JpegEncoderOptions.builder().progressive(true)
                    .density(PixelDensity.ofDotsPerCentimeter(40, 41)).build());
            assertEquals(2, cm[indexOf(cm, 0xE0) + 11]);
            for (var space : new ColorSpace[]{ColorSpace.RGB, ColorSpace.CMYK, ColorSpace.YCCK}) {
                var jpeg = JpegEncoder.encode(picture, JpegEncoderOptions.builder().colorSpace(space).build());
                assertEquals(-1, indexOf(jpeg, 0xE0), space.toString());
                var app14 = indexOf(jpeg, 0xEE);
                assertEquals(2, app14, space.toString());
                assertEquals(space == ColorSpace.YCCK ? 2 : 0, jpeg[app14 + 15], space.toString());
                var sof = indexOf(jpeg, 0xC0);
                var ids = switch (space) {
                    case RGB -> new int[]{'R', 'G', 'B'};
                    case CMYK -> new int[]{'C', 'M', 'Y', 'K'};
                    default -> new int[]{1, 2, 3, 4};
                };
                for (var c = 0; c < ids.length; c++) {
                    assertEquals(ids[c], jpeg[sof + 10 + 3 * c], space + " component " + c);
                }
            }
        }

        @Test
        @DisplayName("a lossless 9-bit YCbCr file of 1x1 components carries an Adobe segment of transform 1")
        void ninebitLossless() {
            var lossless = JpegEncoderOptions.builder().lossless(true).precision(9).chromaFormat(ChromaFormat.YUV444)
                    .density(PixelDensity.ofDotsPerInch(72, 72)).build();
            var jpeg = JpegEncoder.encode(picture(20, 12), lossless);
            assertEquals(-1, indexOf(jpeg, 0xE0));
            var app14 = indexOf(jpeg, 0xEE);
            assertEquals(2, app14);
            assertEquals(1, jpeg[app14 + 15]);
            var subsampled = JpegEncoder.encode(picture(20, 12), lossless.toBuilder()
                    .chromaFormat(ChromaFormat.YUV420).build());
            assertEquals(2, indexOf(subsampled, 0xE0));
            assertEquals(-1, indexOf(subsampled, 0xEE));
            var tenBit = JpegEncoder.encode(picture(20, 12), lossless.toBuilder().precision(10).build());
            assertEquals(2, indexOf(tenBit, 0xE0));
        }
    }

    @Nested
    @DisplayName("JPEG-compressed TIFF pages")
    class Tiff {
        static Stream<Arguments> rejected() {
            var defaults = JpegEncoderOptions.defaults();
            return Stream.of(
                    Arguments.of("progressive", JpegEncoderOptions.ofProgressive(75)),
                    Arguments.of("arithmetic", defaults.toBuilder().arithmetic(true).build()),
                    Arguments.of("lossless", JpegEncoderOptions.ofLossless()),
                    Arguments.of("12-bit", defaults.toBuilder().precision(12).build()),
                    Arguments.of("RGB", defaults.toBuilder().colorSpace(ColorSpace.RGB).build()),
                    Arguments.of("chroma quality", defaults.toBuilder().chromaQuality(40).build()),
                    Arguments.of("sampling factors", defaults.toBuilder().samplingFactors(Sampling.of(1, 2)).build()),
                    Arguments.of("tables", defaults.toBuilder().quantizationTables(QuantizationTable.ANNEX_K_LUMINANCE)
                            .build()),
                    Arguments.of("slots", defaults.toBuilder().quantizationSlots(0, 0, 0).build()),
                    Arguments.of("16-bit quantizers", defaults.toBuilder().baselineQuantization(false).build()),
                    Arguments.of("scan script", defaults.toBuilder().scanScript(Scan.of(0, 63, 0, 0, 0, 1, 2)).build()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("rejected")
        @DisplayName("reject the settings beyond baseline 8-bit YCbCr")
        void rejects(String name, JpegEncoderOptions jpeg) {
            var builder = TiffEncoderOptions.jpeg().toBuilder().jpegOptions(jpeg);
            assertThrows(IllegalArgumentException.class, builder::build, name);
        }

        @Test
        @DisplayName("take the quality, subsampling, Huffman, restart and density settings")
        void accepts() {
            var jpeg = JpegEncoderOptions.builder().quality(40).chromaFormat(ChromaFormat.YUV422).optimizeHuffman(false)
                    .restartInterval(2).precision(8).density(PixelDensity.ofDotsPerInch(300, 300)).build();
            var options = TiffEncoderOptions.jpeg().toBuilder().jpegOptions(jpeg)
                    .build();
            assertEquals(jpeg, options.jpegOptions());
        }
    }
}
