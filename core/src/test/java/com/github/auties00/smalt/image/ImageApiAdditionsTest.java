package com.github.auties00.smalt.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.spi.ImageCodecProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the names added next to the existing image API: builder setters and presets of the image
 * options, the color description helpers, the Optional-returning format detection, the
 * {@link Path} overloads of {@link Images}, and the picture factories and accessors.
 */
@DisplayName("Image API additions")
class ImageApiAdditionsTest {
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    @Nested
    @DisplayName("ImageDecoderOptions")
    class DecoderOptions {
        @Test
        @DisplayName("Builder.fullSize clears the minimum size hint")
        void fullSize() {
            var hinted = ImageDecoderOptions.ofMinimumSize(640, 480);
            var full = hinted.toBuilder().fullSize().build();
            assertEquals(0, full.minWidth());
            assertEquals(0, full.minHeight());
            assertEquals(ImageDecoderOptions.defaults(), full);
        }
    }

    @Nested
    @DisplayName("ImageEncoderOptions")
    class EncoderOptions {
        @Test
        @DisplayName("ofQuality equals the builder's quality setting and validates the range")
        void ofQuality() {
            assertEquals(ImageEncoderOptions.builder().quality(85).build(), ImageEncoderOptions.ofQuality(85));
            assertEquals(OptionalInt.of(1), ImageEncoderOptions.ofQuality(1).quality());
            assertEquals(OptionalInt.of(100), ImageEncoderOptions.ofQuality(100).quality());
            assertThrows(IllegalArgumentException.class, () -> ImageEncoderOptions.ofQuality(0));
            assertThrows(IllegalArgumentException.class, () -> ImageEncoderOptions.ofQuality(101));
        }

        @Test
        @DisplayName("Builder.automaticQuality restores the format's default quality")
        void automaticQuality() {
            var options = ImageEncoderOptions.ofQuality(70).toBuilder().loopCount(2).automaticQuality().build();
            assertEquals(OptionalInt.empty(), options.quality());
            assertEquals(2, options.loopCount());
            assertEquals(ImageEncoderOptions.builder().loopCount(2).build(), options);
        }
    }

    @Nested
    @DisplayName("color description")
    class Color {
        @Test
        @DisplayName("ColorInfo.without* clear the high-dynamic-range metadata")
        void withoutMetadata() {
            var display = new MasteringDisplay(new Chromaticity(0.708, 0.292), new Chromaticity(0.17, 0.797),
                    new Chromaticity(0.131, 0.046), new Chromaticity(0.3127, 0.329), 1000, 0.005);
            var light = new ContentLightLevel(1000, 400);
            var hdr = ColorInfo.BT709_LIMITED.withMasteringDisplay(display).withContentLightLevel(light);
            var noDisplay = hdr.withoutMasteringDisplay();
            assertEquals(Optional.empty(), noDisplay.masteringDisplay());
            assertEquals(Optional.of(light), noDisplay.contentLightLevel());
            var noLight = hdr.withoutContentLightLevel();
            assertEquals(Optional.of(display), noLight.masteringDisplay());
            assertEquals(Optional.empty(), noLight.contentLightLevel());
            assertEquals(ColorInfo.BT709_LIMITED, noDisplay.withoutContentLightLevel());
        }

        @Test
        @DisplayName("ColorPrimaries report the H.273 chromaticities")
        void chromaticities() {
            assertEquals(Optional.of(new Chromaticity(0.64, 0.33)), ColorPrimaries.BT709.red());
            assertEquals(Optional.of(new Chromaticity(0.30, 0.60)), ColorPrimaries.BT709.green());
            assertEquals(Optional.of(new Chromaticity(0.15, 0.06)), ColorPrimaries.BT709.blue());
            assertEquals(Optional.of(new Chromaticity(0.3127, 0.3290)), ColorPrimaries.BT709.white());
            assertEquals(Optional.of(new Chromaticity(0.708, 0.292)), ColorPrimaries.BT2020.red());
            assertEquals(Optional.of(new Chromaticity(0.314, 0.351)), ColorPrimaries.SMPTE431.white());
            assertEquals(Optional.of(new Chromaticity(0.310, 0.316)), ColorPrimaries.BT470M.white());
            assertEquals(ColorPrimaries.SMPTE170M.green(), ColorPrimaries.SMPTE240M.green());
            assertEquals(Optional.of(new Chromaticity(1, 0)), ColorPrimaries.SMPTE428.red());
            assertEquals(Optional.of(new Chromaticity(0, 1)), ColorPrimaries.SMPTE428.green());
            assertEquals(Optional.of(new Chromaticity(0, 0)), ColorPrimaries.SMPTE428.blue());
            assertEquals(Optional.of(new Chromaticity(1.0 / 3, 1.0 / 3)), ColorPrimaries.SMPTE428.white());
        }

        @ParameterizedTest
        @EnumSource(ColorPrimaries.class)
        @DisplayName("ColorPrimaries define all four points or none")
        void chromaticityCompleteness(ColorPrimaries primaries) {
            var defined = primaries != ColorPrimaries.UNSPECIFIED;
            assertEquals(defined, primaries.red().isPresent());
            assertEquals(defined, primaries.green().isPresent());
            assertEquals(defined, primaries.blue().isPresent());
            assertEquals(defined, primaries.white().isPresent());
        }

        @Test
        @DisplayName("MatrixCoefficients report the luma weights of the weighted-sum matrices")
        void lumaWeights() {
            assertEquals(OptionalDouble.of(0.2126), MatrixCoefficients.BT709.kr());
            assertEquals(OptionalDouble.of(0.0722), MatrixCoefficients.BT709.kb());
            assertEquals(OptionalDouble.of(0.299), MatrixCoefficients.BT601.kr());
            assertEquals(MatrixCoefficients.BT601.kb(), MatrixCoefficients.BT470BG.kb());
            assertEquals(OptionalDouble.of(0.2627), MatrixCoefficients.BT2020_NCL.kr());
            assertEquals(OptionalDouble.of(0.30), MatrixCoefficients.FCC.kr());
            assertEquals(OptionalDouble.of(0.087), MatrixCoefficients.SMPTE240M.kb());
        }

        @ParameterizedTest
        @EnumSource(value = MatrixCoefficients.class, names = {"IDENTITY", "UNSPECIFIED", "YCGCO", "BT2020_CL",
                "SMPTE2085", "CHROMA_DERIVED_NCL", "CHROMA_DERIVED_CL", "ICTCP", "IPT_C2", "YCGCO_RE", "YCGCO_RO"})
        @DisplayName("MatrixCoefficients without a weighted-sum luma report no weights")
        void noLumaWeights(MatrixCoefficients matrix) {
            assertEquals(OptionalDouble.empty(), matrix.kr());
            assertEquals(OptionalDouble.empty(), matrix.kb());
        }

        @ParameterizedTest
        @EnumSource(MatrixCoefficients.class)
        @DisplayName("MatrixCoefficients.effective resolves an unspecified matrix to BT.601 and keeps every other")
        void effective(MatrixCoefficients matrix) {
            var expected = matrix == MatrixCoefficients.UNSPECIFIED ? MatrixCoefficients.BT601 : matrix;
            assertEquals(expected, matrix.effective());
            assertEquals(expected.kr(), matrix.effective().kr());
        }
    }

    @Nested
    @DisplayName("format detection")
    class Detection {
        @Test
        @DisplayName("ImageFormat.detect returns an Optional and checks the length")
        void imageFormat() {
            assertEquals(Optional.of(ImageFormat.PNG), ImageFormat.detect(PNG_SIGNATURE, PNG_SIGNATURE.length));
            assertEquals(Optional.empty(), ImageFormat.detect(new byte[16], 16));
            assertEquals(Optional.empty(), ImageFormat.detect(PNG_SIGNATURE, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> ImageFormat.detect(PNG_SIGNATURE, 9));
            assertThrows(IndexOutOfBoundsException.class, () -> ImageFormat.detect(PNG_SIGNATURE, -1));
        }

        @Test
        @DisplayName("Images.detect reads a source or a file")
        void images(@TempDir Path directory) throws IOException {
            assertEquals(Optional.of(ImageFormat.PNG), Images.detect(MediaSource.of(PNG_SIGNATURE)));
            assertEquals(Optional.empty(), Images.detect(MediaSource.of(new byte[100])));
            var file = Files.write(directory.resolve("image.bin"), PNG_SIGNATURE);
            assertEquals(Optional.of(ImageFormat.PNG), Images.detect(file));
            // deleting succeeds on every OS only if the call closed the file
            Files.delete(file);
            assertThrows(MediaException.Io.class, () -> Images.detect(file));
        }

        @ParameterizedTest
        @EnumSource(ImageFormat.class)
        @DisplayName("Images.isDecodable agrees with the installed image codec providers")
        void isDecodable(ImageFormat format) {
            var expected = ServiceLoader.load(ImageCodecProvider.class).stream()
                    .anyMatch(provider -> provider.get().decodes(format));
            assertEquals(expected, Images.isDecodable(format));
        }
    }

    @Nested
    @DisplayName("Images Path overloads")
    class PathOverloads {
        @Test
        @DisplayName("decode and probe fail like the source overloads and close the file")
        void unknownImage(@TempDir Path directory) throws IOException {
            var file = Files.write(directory.resolve("noise.bin"), new byte[256]);
            var decode = assertThrows(MediaException.Unsupported.class, () -> Images.decode(file));
            var source = assertThrows(MediaException.Unsupported.class,
                    () -> Images.decode(MediaSource.of(new byte[256])));
            assertEquals(source.format(), decode.format());
            assertThrows(MediaException.Unsupported.class,
                    () -> Images.decode(file, ImageDecoderOptions.ofMinimumSize(8, 8)));
            assertThrows(MediaException.Unsupported.class, () -> Images.probe(file));
            Files.delete(file);
            assertThrows(MediaException.Io.class, () -> Images.probe(file));
        }

        @Test
        @DisplayName("decode rejects null options before opening the file")
        void nullOptions(@TempDir Path directory) {
            assertThrows(NullPointerException.class, () -> Images.decode(directory.resolve("missing"), null));
        }
    }

    @Nested
    @DisplayName("pictures")
    class Pictures {
        @Test
        @DisplayName("YuvPicture.chromaFormat reports the chroma format and the builder accepts it")
        void yuv() {
            var picture = YuvPicture.allocate(6, 4, ChromaFormat.YUV420, true, ColorInfo.JPEG);
            assertEquals(ChromaFormat.YUV420, picture.chromaFormat());
            var rebuilt = picture.toBuilder().chromaFormat(ChromaFormat.YUV420).build();
            assertEquals(ChromaFormat.YUV420, rebuilt.chromaFormat());
            assertSame(picture.y(), rebuilt.y());
            assertThrows(NullPointerException.class, () -> YuvPicture.builder().chromaFormat(null));
        }

        @Test
        @DisplayName("Yuv16Picture.chromaFormat reports the chroma format and the builder accepts it")
        void yuv16() {
            var picture = Yuv16Picture.allocate(4, 4, ChromaFormat.YUV444, 10, false, ColorInfo.BT709_LIMITED);
            assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
            var rebuilt = picture.toBuilder().chromaFormat(ChromaFormat.YUV444).build();
            assertEquals(ChromaFormat.YUV444, rebuilt.chromaFormat());
            assertThrows(NullPointerException.class, () -> Yuv16Picture.builder().chromaFormat(null));
        }

        @Test
        @DisplayName("RgbaPicture factories match the constructors")
        void rgba() {
            var allocated = RgbaPicture.allocate(3, 2, true);
            assertEquals(3, allocated.width());
            assertEquals(12, allocated.stride());
            assertTrue(allocated.hasAlpha());
            assertEquals(24, allocated.pixels().length);
            var pixels = new byte[40];
            var wrapped = RgbaPicture.of(3, 2, 16, pixels, false);
            assertSame(pixels, wrapped.pixels());
            assertEquals(16, wrapped.stride());
            assertFalse(wrapped.hasAlpha());
            assertThrows(IllegalArgumentException.class, () -> RgbaPicture.allocate(0, 2, false));
            assertThrows(IllegalArgumentException.class, () -> RgbaPicture.of(3, 2, 8, pixels, false));
            assertThrows(IllegalArgumentException.class, () -> RgbaPicture.of(3, 2, 16, new byte[20], false));
        }

        @Test
        @DisplayName("Rgba16Picture factories match the constructors")
        void rgba16() {
            var allocated = Rgba16Picture.allocate(2, 2, false);
            assertEquals(8, allocated.stride());
            assertEquals(16, allocated.pixels().length);
            assertFalse(allocated.hasAlpha());
            var pixels = new short[16];
            var wrapped = Rgba16Picture.of(2, 2, 8, pixels, true);
            assertSame(pixels, wrapped.pixels());
            assertTrue(wrapped.hasAlpha());
            assertNotSame(allocated.pixels(), wrapped.pixels());
            assertThrows(IllegalArgumentException.class, () -> Rgba16Picture.of(2, 2, 4, pixels, true));
        }
    }
}
