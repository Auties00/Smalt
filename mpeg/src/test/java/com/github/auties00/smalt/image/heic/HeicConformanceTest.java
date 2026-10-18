package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifOverlay;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.image.heic.HeicReference.Planes;
import com.github.auties00.smalt.image.heic.internal.StillDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the Nokia HEIF conformance files and real camera HEIC files and compares every sample
 * with an independent reconstruction that decodes the HEVC items with ffmpeg.
 */
@DisplayName("HEIC conformance")
class HeicConformanceTest {
    private static MediaSource source(Path path) {
        return MediaSource.of(HeicVectors.read(path));
    }

    private static HeifFile parse(MediaSource source) {
        return HeifFile.parse(source, MediaLimits.DEFAULT, false);
    }

    private static Picture reconstruct(MediaSource source, HeifFile file, int item) {
        return StillDecoder.decode(source, file, file.item(item), MediaLimits.DEFAULT, false).picture();
    }

    private static void assertPlanes(Planes expected, Planes actual, String label) {
        assertEquals(expected.width(), actual.width(), label + ": width");
        assertEquals(expected.height(), actual.height(), label + ": height");
        assertEquals(expected.format(), actual.format(), label + ": chroma format");
        assertEquals(expected.depth(), actual.depth(), label + ": bit depth");
        for (var plane = 0; plane < 4; plane++) {
            var want = expected.planes()[plane];
            var got = actual.planes()[plane];
            assertEquals(want == null, got == null, label + ": presence of plane " + plane);
            if (want == null) {
                continue;
            }
            if (!Arrays.equals(want, got)) {
                var index = Arrays.mismatch(want, got);
                throw new AssertionError(label + ": plane " + plane + " differs at sample " + index + " (expected "
                        + want[index] + ", got " + got[index] + ")");
            }
        }
    }

    private static Planes withoutAlpha(Planes planes) {
        return planes.withAlpha(null);
    }

    private static Planes rotateAnticlockwise(Planes planes) {
        var out = new int[4][];
        out[0] = rotate(planes.planes()[0], planes.width(), planes.height());
        if (planes.planes()[1] != null) {
            out[1] = rotate(planes.planes()[1], planes.chromaWidth(), planes.chromaHeight());
            out[2] = rotate(planes.planes()[2], planes.chromaWidth(), planes.chromaHeight());
        }
        return new Planes(planes.height(), planes.width(), planes.format(), planes.depth(), out);
    }

    private static int[] rotate(int[] plane, int width, int height) {
        var out = new int[plane.length];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                // a quarter turn anticlockwise: the right column becomes the top row
                out[(width - 1 - x) * height + y] = plane[y * width + x];
            }
        }
        return out;
    }

    private static Planes reference(MediaSource source, HeifFile file, int item, DecodedImage decoded) {
        var planes = Planes.of(decoded.picture());
        return HeicReference.item(source, file, file.item(item), planes.width(), planes.height(), planes.format(),
                planes.depth());
    }

    @Nested
    @DisplayName("coded primary images match ffmpeg's decode of their HEVC payload")
    class Coded {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "C002.heic, 1002", "C003.heic, 1002", "C004.heic, 1002", "C005.heic, 1005", "C009.heic, 1002",
                "C010.heic, 1002", "C011.heic, 1002", "C012.heic, 1002", "C023.heic, 1002", "C034.heic, 1002",
                "C040.heic, 1014", "C043.heic, 1002", "C044.heic, 1004", "C045.heic, 1002", "C047.heic, 1002",
                "C049.heic, 1820", "C050.heic, 1002", "C051.heic, 1002", "C053.heic, 1002", "MIAF001.heic, 1002",
                "MIAF004.heic, 1005", "multilayer001.heic, 20003", "multilayer003.heic, 1002",
                "multilayer005.heic, 20003"
        })
        @DisplayName("Nokia files")
        void nokia(String name, int item) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.nokia(name));
            var decoded = HeicDecoder.decode(source);
            assertEquals(ImageFormat.HEIC, decoded.format());
            assertEquals(Orientation.NORMAL, decoded.orientation());
            assertInstanceOf(YuvPicture.class, decoded.picture());
            assertPlanes(reference(source, parse(source), item, decoded), Planes.of(decoded.picture()), name);
        }

        @Test
        @DisplayName("a 10-bit Main 10 image matches at its depth, and its 8-bit decode is the reduced deep one")
        void tenBit() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.nokia("MIAF002.heic"));
            var deep = HeicDecoder.decode(source, ImageDecoderOptions.builder().highBitDepth(true).build());
            var picture = assertInstanceOf(Yuv16Picture.class, deep.picture());
            assertEquals(10, picture.bitDepth());
            assertPlanes(reference(source, parse(source), 1002, deep), Planes.of(picture), "MIAF002");
            var narrow = HeicDecoder.decode(source);
            assertPlanes(Planes.of(ImageOps.toEightBit(picture)), Planes.of(narrow.picture()), "MIAF002 8-bit");
        }

        @Test
        @DisplayName("a reduced-size hint decodes the smallest thumbnail that is large enough")
        void thumbnail() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.nokia("MIAF001.heic"));
            var decoded = HeicDecoder.decode(source, ImageDecoderOptions.ofMinimumSize(100, 50));
            assertEquals(128, decoded.picture().width());
            assertEquals(72, decoded.picture().height());
            assertEquals(1280, decoded.sourceWidth());
            assertEquals(720, decoded.sourceHeight());
            assertPlanes(reference(source, parse(source), 1005, decoded), Planes.of(decoded.picture()), "thumbnail");
            var full = HeicDecoder.decode(source, ImageDecoderOptions.ofMinimumSize(200, 100));
            assertEquals(1280, full.picture().width());
        }
    }

    @Nested
    @DisplayName("grids match the ffmpeg-decoded tiles stitched together")
    class Grids {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"C007.heic, 1009", "C022.heic, 1009", "C024.heic, 1003", "C025.heic, 1021"})
        @DisplayName("Nokia files")
        void nokia(String name, int item) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.nokia(name));
            var file = parse(source);
            var expected = HeicReference.grid(source, file, file.item(item), ChromaFormat.YUV420, 8);
            assertPlanes(expected, Planes.of(reconstruct(source, file, item)), name);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "tests/images/heif_other/pug.heic, NORMAL, true, true",
                "tests/images/heif_other/arrow.heic, ROTATE_90, true, true",
                "tests/images/heif_special/xiaomi.heic, NORMAL, false, true",
                "tests/images/heif_other/spatial_photo.heic, NORMAL, true, true"
        })
        @DisplayName("phone photos")
        void phones(String path, Orientation orientation, boolean icc, boolean profile) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.pillow(path));
            var decoded = HeicDecoder.decode(source);
            var file = parse(source);
            var expected = HeicReference.grid(source, file, file.primaryItem(), ChromaFormat.YUV420, 8);
            assertPlanes(expected, Planes.of(decoded.picture()), path);
            assertEquals(orientation, decoded.orientation());
            assertTrue(decoded.exif().isPresent(), "EXIF");
            assertEquals(icc, decoded.iccProfile().isPresent(), "ICC profile");
            // without an ICC profile the BT.601 primaries of the stream are a color profile too
            assertEquals(profile, decoded.colorProfile().isPresent(), "color profile");
        }

        @Test
        @DisplayName("a 10-bit grid with an alpha grid, cropped by the grid output size")
        void tenBitAlpha() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.pillow("tests/images/heif/RGBA_10__29x100.heif"));
            var file = parse(source);
            var decoded = HeicDecoder.decode(source, ImageDecoderOptions.builder().highBitDepth(true).build());
            var color = HeicReference.item(source, file, file.item(1), 64, 100, ChromaFormat.YUV420, 10);
            var alpha = HeicReference.item(source, file, file.item(3), 64, 100, ChromaFormat.MONOCHROME, 10);
            var expected = color.crop(0, 0, 29, 100).withAlpha(alpha.crop(0, 0, 29, 100).planes()[0]);
            assertPlanes(expected, Planes.of(decoded.picture()), "RGBA_10");
        }
    }

    @Nested
    @DisplayName("transformative properties")
    class Transforms {
        @Test
        @DisplayName("an identity derivation with a quarter turn reports the turn")
        void identityRotation() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.nokia("C008.heic"));
            var decoded = HeicDecoder.decode(source);
            assertEquals(Orientation.ROTATE_270, decoded.orientation());
            assertPlanes(reference(source, parse(source), 1005, decoded), Planes.of(decoded.picture()), "C008");
        }

        @Test
        @DisplayName("a clean aperture crops the center")
        void cleanAperture() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.nokia("C013.heic"));
            var file = parse(source);
            var decoded = HeicDecoder.decode(source);
            var full = HeicReference.item(source, file, file.item(1002), 1280, 720, ChromaFormat.YUV420, 8);
            assertPlanes(full.crop(490, 210, 300, 300), Planes.of(decoded.picture()), "C013");
            assertEquals(Orientation.NORMAL, decoded.orientation());
        }

        @Test
        @DisplayName("an identity derivation with a clean aperture and a quarter turn")
        void identityCropRotation() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.nokia("C014.heic"));
            var file = parse(source);
            var decoded = HeicDecoder.decode(source);
            var full = HeicReference.item(source, file, file.item(1006), 1280, 720, ChromaFormat.YUV420, 8);
            assertPlanes(full.crop(490, 210, 300, 300), Planes.of(decoded.picture()), "C014");
            assertEquals(Orientation.ROTATE_270, decoded.orientation());
        }

        @Test
        @DisplayName("nested identity derivations apply the inner transforms to the pixels")
        void nestedIdentity() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.nokia("C039.heic"));
            var file = parse(source);
            var decoded = HeicDecoder.decode(source);
            var full = HeicReference.item(source, file, file.item(1002), 1280, 720, ChromaFormat.YUV420, 8);
            var inner = rotateAnticlockwise(full.crop(490, 210, 300, 300));
            // the outer aperture starts at an odd sample, so the crop is 4:4:4
            assertPlanes(inner.cropExact(75, 75, 150, 150), Planes.of(decoded.picture()), "C039");
            assertEquals(Orientation.ROTATE_270, decoded.orientation());
        }

        @Test
        @DisplayName("a vertical mirror is reported as a vertical flip")
        void mirror() {
            var decoded = HeicDecoder.decode(source(HeicVectors.nokia("C042.heic")));
            assertEquals(Orientation.FLIP_VERTICAL, decoded.orientation());
            assertEquals(1280, decoded.picture().width());
        }

        @Test
        @DisplayName("a crop, a quarter turn and a mirror compose into a transpose")
        void composed() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.nokia("MIAF007.heic"));
            var file = parse(source);
            var decoded = HeicDecoder.decode(source);
            var full = HeicReference.item(source, file, file.item(1002), 1280, 720, ChromaFormat.YUV420, 8);
            assertPlanes(full.crop(320, 180, 640, 360), Planes.of(decoded.picture()), "MIAF007");
            assertEquals(Orientation.TRANSPOSE, decoded.orientation());
            var info = HeicDecoder.probe(source);
            assertEquals(640, info.width());
            assertEquals(Orientation.TRANSPOSE, info.orientation());
        }

        @Test
        @DisplayName("a clean aperture with a half-sample offset on a monochrome image")
        void monochromeCrop() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.pillow("tests/images/heif/L_8__29x100.heif"));
            var file = parse(source);
            var decoded = HeicDecoder.decode(source);
            var full = HeicReference.item(source, file, file.item(1), 64, 100, ChromaFormat.MONOCHROME, 8);
            assertPlanes(full.crop(0, 0, 29, 100), Planes.of(decoded.picture()), "L_8");
        }
    }

    @Nested
    @DisplayName("alpha")
    class Alpha {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"C006.heic, 1002, 1005", "C052.heic, 1002, 1005"})
        @DisplayName("Nokia alpha auxiliary images")
        void nokia(String name, int color, int alpha) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.nokia(name));
            var file = parse(source);
            var decoded = HeicDecoder.decode(source);
            var expectedColor = HeicReference.item(source, file, file.item(color), 1280, 720, ChromaFormat.YUV420, 8);
            var expectedAlpha = HeicReference.item(source, file, file.item(alpha), 1280, 720, ChromaFormat.YUV420, 8);
            var actual = Planes.of(decoded.picture());
            assertPlanes(expectedColor, withoutAlpha(actual), name + " color");
            assertArrayEquals(expectedAlpha.planes()[0], actual.planes()[3], name + " alpha");
            assertTrue(HeicDecoder.probe(source).hasAlpha());
        }

        @Test
        @DisplayName("an 8-bit image with a monochrome alpha item")
        void pillow() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.pillow("tests/images/heif/RGBA_8__128x128.heif"));
            var file = parse(source);
            var decoded = HeicDecoder.decode(source);
            var color = HeicReference.item(source, file, file.item(1), 128, 128, ChromaFormat.YUV420, 8);
            var alpha = HeicReference.item(source, file, file.item(2), 128, 128, ChromaFormat.MONOCHROME, 8);
            assertPlanes(color.withAlpha(alpha.planes()[0]), Planes.of(decoded.picture()), "RGBA_8");
        }
    }

    @Nested
    @DisplayName("overlays match their inputs composed over the canvas")
    class Overlays {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"C015.heic", "C016.heic", "C017.heic", "C018.heic", "C019.heic", "C020.heic", "C021.heic"})
        @DisplayName("Nokia files")
        void nokia(String name) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.nokia(name));
            var file = parse(source);
            var overlayItem = file.items().stream().filter(item -> item.type().equals("iovl")).findFirst().orElseThrow();
            var composed = reconstruct(source, file, overlayItem.id());
            var ids = overlayItem.references("dimg");
            var overlay = HeifOverlay.parse(file.readItem(source, overlayItem, MediaLimits.DEFAULT), ids.length);
            var width = (int) overlay.outputWidth();
            var height = (int) overlay.outputHeight();
            var expected = new byte[width * height * 4];
            for (var i = 0; i < expected.length; i++) {
                expected[i] = (byte) ((overlay.fill()[i & 3] * 255 + 32767) / 65535);
            }
            for (var i = 0; i < ids.length; i++) {
                var input = file.item(ids[i]);
                var extent = input.property(com.github.auties00.smalt.internal.heif.HeifProperty.SpatialExtent.class);
                var planes = HeicReference.item(source, file, input, (int) extent.width(), (int) extent.height(),
                        ChromaFormat.YUV420, 8);
                var rgba = ImageOps.toRgba(toPicture(planes, MIAF_DEFAULT));
                paste(expected, width, height, rgba, overlay.offsetsX()[i], overlay.offsetsY()[i]);
            }
            var picture = assertInstanceOf(RgbaPicture.class, composed);
            assertEquals(width, picture.width());
            assertEquals(height, picture.height());
            var actual = new byte[width * height * 4];
            for (var y = 0; y < height; y++) {
                System.arraycopy(picture.pixels(), y * picture.stride(), actual, y * width * 4, width * 4);
            }
            assertArrayEquals(expected, actual, name);
        }

        @Test
        @DisplayName("an overlay whose input has an alpha plane blends it over the canvas")
        void alphaInput() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.pillow("tests/images/heif_other/nokia/alpha.heic"));
            var file = parse(source);
            var decoded = HeicDecoder.decode(source);
            var overlayItem = file.primaryItem();
            var ids = overlayItem.references("dimg");
            var overlay = HeifOverlay.parse(file.readItem(source, overlayItem, MediaLimits.DEFAULT), ids.length);
            var width = (int) overlay.outputWidth();
            var height = (int) overlay.outputHeight();
            var expected = new int[width * height * 4];
            for (var i = 0; i < expected.length; i++) {
                expected[i] = (overlay.fill()[i & 3] * 255 + 32767) / 65535;
            }
            var bottom = HeicReference.item(source, file, file.item(1002), 1440, 960, ChromaFormat.YUV420, 8);
            var top = HeicReference.item(source, file, file.item(1005), 1440, 960, ChromaFormat.YUV420, 8);
            var alpha = HeicReference.item(source, file, file.item(1008), 1440, 960, ChromaFormat.YUV420, 8).planes()[0];
            blend(expected, width, height, ImageOps.toRgba(toPicture(bottom, MIAF_DEFAULT)), null,
                    overlay.offsetsX()[0], overlay.offsetsY()[0]);
            blend(expected, width, height, ImageOps.toRgba(toPicture(top, MIAF_DEFAULT)), alpha,
                    overlay.offsetsX()[1], overlay.offsetsY()[1]);
            var picture = assertInstanceOf(RgbaPicture.class, decoded.picture());
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width * 4; x++) {
                    var actual = picture.pixels()[y * picture.stride() + x] & 0xFF;
                    if (actual != expected[y * width * 4 + x]) {
                        throw new AssertionError("pixel " + (x / 4) + "," + y + " channel " + (x & 3) + ": expected "
                                + expected[y * width * 4 + x] + ", got " + actual);
                    }
                }
            }
        }

        // source-over in straight alpha
        private static void blend(int[] canvas, int width, int height, RgbaPicture picture, int[] alpha, long left,
                                  long top) {
            for (var y = 0; y < picture.height(); y++) {
                for (var x = 0; x < picture.width(); x++) {
                    var row = top + y;
                    var column = left + x;
                    if (row < 0 || row >= height || column < 0 || column >= width) {
                        continue;
                    }
                    var out = (int) (row * width + column) * 4;
                    var in = y * picture.stride() + x * 4;
                    var a = alpha == null ? 255 : alpha[y * picture.width() + x];
                    if (a == 255) {
                        for (var c = 0; c < 3; c++) {
                            canvas[out + c] = picture.pixels()[in + c] & 0xFF;
                        }
                        canvas[out + 3] = 255;
                        continue;
                    }
                    if (a == 0) {
                        continue;
                    }
                    var below = canvas[out + 3];
                    var coverage = a * 255 + below * (255 - a);
                    for (var c = 0; c < 3; c++) {
                        canvas[out + c] = ((picture.pixels()[in + c] & 0xFF) * a * 255
                                + canvas[out + c] * below * (255 - a) + coverage / 2) / coverage;
                    }
                    canvas[out + 3] = (coverage + 127) / 255;
                }
            }
        }

        private static void paste(byte[] canvas, int width, int height, RgbaPicture picture, long left, long top) {
            for (var y = 0; y < picture.height(); y++) {
                var row = top + y;
                if (row < 0 || row >= height) {
                    continue;
                }
                for (var x = 0; x < picture.width(); x++) {
                    var column = left + x;
                    if (column < 0 || column >= width) {
                        continue;
                    }
                    System.arraycopy(picture.pixels(), y * picture.stride() + x * 4, canvas,
                            (int) (row * width + column) * 4, 4);
                }
            }
        }
    }

    // the Nokia streams carry no color description: the MIAF defaults apply
    private static final ColorInfo MIAF_DEFAULT = new ColorInfo(com.github.auties00.smalt.image.MatrixCoefficients.BT601,
            com.github.auties00.smalt.image.ColorRange.FULL, com.github.auties00.smalt.image.ColorPrimaries.BT709,
            com.github.auties00.smalt.image.TransferCharacteristics.SRGB);

    private static Picture toPicture(Planes planes, ColorInfo color) {
        var builder = YuvPicture.builder().size(planes.width(), planes.height()).chromaFormat(planes.format()).color(color);
        builder.luma(pack(planes.planes()[0]), planes.width());
        if (planes.planes()[1] != null) {
            builder.chroma(pack(planes.planes()[1]), pack(planes.planes()[2]), planes.chromaWidth());
        }
        return builder.build();
    }

    private static byte[] pack(int[] samples) {
        var out = new byte[samples.length];
        for (var i = 0; i < samples.length; i++) {
            out[i] = (byte) samples[i];
        }
        return out;
    }

    @Nested
    @DisplayName("image sequences play every frame ffmpeg decodes")
    class Sequences {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "C001.heic, 8, false", "C026.heic, 8, false", "C027.heic, 16, false", "C028.heic, 16, false",
                "C029.heic, 8, false", "C030.heic, 8, false", "C031.heic, 8, false", "C032.heic, 8, false",
                "C036.heic, 8, false", "C037.heic, 8, false", "C038.heic, 8, false", "C041.heic, 8, true",
                "C048.heic, 8, false", "MIAF005.heic, 8, false", "MIAF006.heic, 3, false"
        })
        @DisplayName("Nokia files")
        void nokia(String name, int frames, boolean editList) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var path = HeicVectors.nokia(name);
            var source = source(path);
            var first = HeicDecoder.decode(source);
            var color = ((YuvPicture) first.picture()).color();
            var actual = new ArrayList<RgbaPicture>();
            try (var reader = HeicDecoder.openAnimation(source)) {
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    actual.add((RgbaPicture) frame.picture());
                }
            }
            // ffmpeg ignores the edit lists that repeat or delay samples, and applies the one that hides
            // the first sample of C041, as the decoder does
            var expected = HeicReference.decodeFile(path, actual.getFirst().width(), actual.getFirst().height(),
                    ChromaFormat.YUV420, 8, editList);
            assertEquals(frames, actual.size(), name + " frame count");
            assertEquals(expected.size(), actual.size(), name + " frames against ffmpeg");
            for (var i = 0; i < expected.size(); i++) {
                var want = ImageOps.toRgba(toPicture(expected.get(i), color));
                assertArrayEquals(want.pixels(), actual.get(i).pixels(), name + " frame " + i);
            }
        }

        @Test
        @DisplayName("loop counts follow the edit lists")
        void loops() throws Exception {
            try (var forever = HeicDecoder.openAnimation(source(HeicVectors.nokia("C038.heic")));
                 var none = HeicDecoder.openAnimation(source(HeicVectors.nokia("C026.heic")))) {
                assertEquals(0, forever.loopCount());
                assertEquals(0, none.loopCount());
            }
            try (var twice = HeicDecoder.openAnimation(source(HeicVectors.nokia("C036.heic")))) {
                assertTrue(twice.loopCount() > 1, "a repeating edit list repeats");
            }
        }

        @Test
        @DisplayName("probing a sequence reports its frame count")
        void probe() {
            var info = HeicDecoder.probe(source(HeicVectors.nokia("C026.heic")));
            assertTrue(info.animated());
            assertEquals(8, info.frameCount());
            assertEquals(1280, info.width());
        }
    }

    @Nested
    @DisplayName("unsupported features raise MediaException.Unsupported")
    class Unsupported {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"multilayer004.heic"})
        @DisplayName("Nokia files")
        void nokia(String name) {
            assertThrows(MediaException.Unsupported.class, () -> HeicDecoder.decode(source(HeicVectors.nokia(name))));
        }

    }

    @Nested
    @DisplayName("camera images in the range extension formats")
    class Camera {
        @Test
        @DisplayName("a 10-bit 4:2:2 grid matches ffmpeg at its depth and in its chroma format")
        void canon() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.pillow("tests/images/heif_other/cat.hif"));
            var decoded = HeicDecoder.decode(source, ImageDecoderOptions.builder().highBitDepth(true).build());
            var picture = assertInstanceOf(Yuv16Picture.class, decoded.picture());
            assertEquals(ChromaFormat.YUV422, picture.chromaFormat());
            assertEquals(10, picture.bitDepth());
            assertEquals(4240, picture.width());
            var file = parse(source);
            var expected = HeicReference.grid(source, file, file.primaryItem(), ChromaFormat.YUV422, 10);
            assertPlanes(expected, Planes.of(picture), "cat");
            var narrow = HeicDecoder.decode(source);
            assertPlanes(Planes.of(ImageOps.toEightBit(picture)), Planes.of(narrow.picture()), "cat 8-bit");
        }

        @Test
        @DisplayName("a 4:4:4 image matches ffmpeg in its chroma format")
        void fourFourFour() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.nokia("MIAF003.heic"));
            var decoded = HeicDecoder.decode(source);
            var picture = assertInstanceOf(YuvPicture.class, decoded.picture());
            assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
            var file = parse(source);
            assertPlanes(reference(source, file, file.primaryItem().id(), decoded), Planes.of(picture), "MIAF003");
        }

        @Test
        @DisplayName("the 4:2:0 thumbnail of a 4:2:2 image decodes under a size hint")
        void canonThumbnail() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = source(HeicVectors.pillow("tests/images/heif_other/cat.hif"));
            var thumbnail = HeicDecoder.decode(source, ImageDecoderOptions.ofMinimumSize(300, 200).toBuilder().highBitDepth(true).build());
            assertEquals(320, thumbnail.picture().width());
            assertEquals(4240, thumbnail.sourceWidth());
            assertPlanes(reference(source, parse(source), 7, thumbnail), Planes.of(thumbnail.picture()), "cat thumbnail");
            assertEquals(10, thumbnail.picture().bitDepth());
        }
    }

    @Nested
    @DisplayName("metadata and registry")
    class Metadata {
        @Test
        @DisplayName("the EXIF item is returned as a TIFF structure")
        void exif() {
            for (var path : List.of(HeicVectors.nokia("C034.heic"),
                    HeicVectors.pillow("tests/images/heif_other/L_exif_xmp_iptc.heic"))) {
                var exif = HeicDecoder.decode(source(path)).exif().orElseThrow();
                assertTrue((exif[0] == 'I' && exif[1] == 'I') || (exif[0] == 'M' && exif[1] == 'M'), path.toString());
            }
        }

        @Test
        @DisplayName("Images recognizes, probes and decodes HEIC through the registry")
        void registry() {
            assertTrue(Images.isDecodable(ImageFormat.HEIC));
            var bytes = HeicVectors.read(HeicVectors.pillow("tests/images/heif_other/pug.heic"));
            assertEquals(Optional.of(ImageFormat.HEIC), Images.detect(MediaSource.of(bytes)));
            var info = Images.probe(MediaSource.of(bytes));
            assertEquals(4032, info.width());
            assertEquals(3024, info.height());
            assertFalse(info.animated());
            var decoded = Images.decode(MediaSource.of(bytes));
            assertEquals(4032, decoded.picture().width());
        }

        @Test
        @DisplayName("an ICC profile is the color profile and converts to sRGB on request")
        void colorProfile() {
            var source = source(HeicVectors.pillow("tests/images/heif_other/pug.heic"));
            var decoded = HeicDecoder.decode(source);
            assertNotNull(decoded.colorProfile().orElseThrow());
            assertFalse(decoded.colorProfile().orElseThrow().isSrgb());
            var srgb = HeicDecoder.decode(source, ImageDecoderOptions.builder().convertToSrgb(true).build());
            assertInstanceOf(RgbaPicture.class, srgb.picture());
        }
    }
}
