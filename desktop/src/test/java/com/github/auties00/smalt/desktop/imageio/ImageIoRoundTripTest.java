package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.webp.WebpContainer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ImageIO round trips")
class ImageIoRoundTripTest {
    static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    static int[] pixels(RgbaPicture picture) {
        var result = new int[picture.width() * picture.height()];
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                result[y * picture.width() + x] = BufferedImagesTest.argb(picture, x, y);
            }
        }
        return result;
    }

    static byte[] write(BufferedImage image, Consumer<ImageWriteParam> configure) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("webp").next();
        var param = writer.getDefaultWriteParam();
        configure.accept(param);
        var bytes = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    static byte[] lossless(BufferedImage image) throws IOException {
        return write(image, param -> {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType(SmaltImageWriteParam.LOSSLESS);
        });
    }

    static BufferedImage read(byte[] data, ImageReadParam param) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            var reader = ImageIO.getImageReaders(input).next();
            assertInstanceOf(SmaltImageReader.class, reader);
            reader.setInput(input);
            try {
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    static BufferedImage image(boolean alpha) {
        return BufferedImages.toBufferedImage(BufferedImagesTest.gradient(97, 61, alpha));
    }

    @Nested
    @DisplayName("WebP writer")
    class Writer {
        @Test
        @DisplayName("lossless output reads back sample-exact with alpha")
        void losslessAlpha() throws IOException {
            var image = image(true);
            var decoded = ImageIO.read(new ByteArrayInputStream(lossless(image)));
            assertEquals(BufferedImage.TYPE_INT_ARGB, decoded.getType());
            assertArrayEquals(pixels(image), pixels(decoded));
        }

        @Test
        @DisplayName("disabled compression is lossless too")
        void disabled() throws IOException {
            var image = image(false);
            var data = write(image, param -> param.setCompressionMode(ImageWriteParam.MODE_DISABLED));
            var decoded = ImageIO.read(new ByteArrayInputStream(data));
            assertEquals(BufferedImage.TYPE_INT_RGB, decoded.getType());
            assertArrayEquals(pixels(image), pixels(decoded));
        }

        @Test
        @DisplayName("an explicit quality maps to the same bytes as the registry's quality")
        void quality() throws IOException {
            var image = image(false);
            var data = write(image, param -> {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.9f);
            });
            var expected = Images.encode(BufferedImages.toPicture(image), ImageFormat.WEBP, ImageEncoderOptions.ofQuality(90));
            assertArrayEquals(expected, data);
        }

        @Test
        @DisplayName("ImageIO.write uses the encoder's defaults")
        void defaults(@TempDir Path directory) throws IOException {
            var image = image(true);
            var file = directory.resolve("out.webp");
            assertTrue(ImageIO.write(image, "webp", file.toFile()));
            var expected = Images.encode(BufferedImages.toPicture(image), ImageFormat.WEBP, ImageEncoderOptions.defaults());
            assertArrayEquals(expected, Files.readAllBytes(file));
        }

        @Test
        @DisplayName("source region and subsampling select the written pixels")
        void region() throws IOException {
            var image = image(true);
            var data = write(image, param -> {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionType(SmaltImageWriteParam.LOSSLESS);
                param.setSourceRegion(new Rectangle(4, 6, 50, 40));
                param.setSourceSubsampling(3, 2, 1, 1);
            });
            var decoded = ImageIO.read(new ByteArrayInputStream(data));
            assertEquals(17, decoded.getWidth());
            assertEquals(20, decoded.getHeight());
            assertEquals(image.getRGB(4 + 1 + 3 * 16, 6 + 1 + 2 * 19), decoded.getRGB(16, 19));
        }

        @Test
        @DisplayName("a gray image is written with equal channels")
        void gray() throws IOException {
            var image = new BufferedImage(31, 9, BufferedImage.TYPE_BYTE_GRAY);
            for (var i = 0; i < 31 * 9; i++) {
                image.getRaster().setSample(i % 31, i / 31, 0, (i * 37) & 0xFF);
            }
            var decoded = ImageIO.read(new ByteArrayInputStream(lossless(image)));
            for (var i = 0; i < 31 * 9; i++) {
                var gray = (i * 37) & 0xFF;
                assertEquals(0xFF000000 | gray * 0x010101, decoded.getRGB(i % 31, i / 31));
            }
        }
    }

    @Nested
    @DisplayName("JPEG XL writer")
    class JxlWriter {
        @Test
        @DisplayName("ImageIO.write writes lossless and lossy files that read back")
        void roundTrip(@TempDir Path directory) throws IOException {
            var image = image(true);
            var writer = ImageIO.getImageWritersByFormatName("jxl").next();
            var param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType(SmaltImageWriteParam.LOSSLESS);
            var out = new java.io.ByteArrayOutputStream();
            try (var stream = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
            }
            writer.dispose();
            var decoded = ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
            assertArrayEquals(pixels(image), pixels(decoded));
            var file = directory.resolve("out.jxl");
            assertTrue(ImageIO.write(image, "jxl", file.toFile()));
            var expected = Images.encode(BufferedImages.toPicture(image), ImageFormat.JXL,
                    ImageEncoderOptions.defaults());
            assertArrayEquals(expected, Files.readAllBytes(file));
        }
    }

    @Nested
    @DisplayName("WebP reader")
    class Reader {
        @Test
        @DisplayName("a lossy image reads as the registry decodes it")
        void lossy() throws IOException {
            var data = Images.encode(BufferedImagesTest.gradient(97, 61, false), ImageFormat.WEBP, ImageEncoderOptions.ofQuality(80));
            var decoded = ImageIO.read(new ByteArrayInputStream(data));
            var expected = ImageOps.toRgba(Images.decode(MediaSource.of(data)).picture());
            assertEquals(BufferedImage.TYPE_INT_RGB, decoded.getType());
            assertArrayEquals(pixels(expected), pixels(decoded));
        }

        @Test
        @DisplayName("files, length-less streams and byte streams read alike")
        void inputs(@TempDir Path directory) throws IOException {
            var data = lossless(image(true));
            var file = directory.resolve("in.webp");
            Files.write(file, data);
            var fromFile = ImageIO.read(file.toFile());
            // ImageIO.read closes the stream it is given
            var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            assertEquals(-1, stream.length());
            var fromStream = ImageIO.read(stream);
            assertArrayEquals(pixels(fromFile), pixels(fromStream));
            assertArrayEquals(pixels(fromFile), pixels(ImageIO.read(new ByteArrayInputStream(data))));
        }

        static Stream<Arguments> params() {
            return Stream.of(
                    Arguments.of("region", (Consumer<ImageReadParam>) param -> param.setSourceRegion(new Rectangle(5, 3, 40, 30))),
                    Arguments.of("subsampling", (Consumer<ImageReadParam>) param -> param.setSourceSubsampling(3, 2, 0, 0)),
                    Arguments.of("region, subsampling and offsets", (Consumer<ImageReadParam>) param -> {
                        param.setSourceRegion(new Rectangle(7, 4, 61, 50));
                        param.setSourceSubsampling(4, 3, 2, 1);
                    }),
                    Arguments.of("destination offset", (Consumer<ImageReadParam>) param -> param.setDestinationOffset(new Point(9, 4))),
                    Arguments.of("negative destination offset", (Consumer<ImageReadParam>) param -> param.setDestinationOffset(new Point(-5, -2))),
                    Arguments.of("destination type", (Consumer<ImageReadParam>) param ->
                            param.setDestinationType(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_4BYTE_ABGR))));
        }

        // The JDK's PNG reader is the reference for ImageReadParam semantics: the same pixels as a
        // PNG must read the same through it.
        @ParameterizedTest(name = "{0}")
        @MethodSource("params")
        @DisplayName("read parameters behave as in the JDK's PNG reader")
        void readParam(String name, Consumer<ImageReadParam> configure) throws IOException {
            var image = image(true);
            var png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            BufferedImage expected;
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(png.toByteArray()))) {
                var reader = ImageIO.getImageReadersByFormatName("png").next();
                reader.setInput(input);
                var param = reader.getDefaultReadParam();
                configure.accept(param);
                expected = reader.read(0, param);
            }
            var param = new ImageReadParam();
            configure.accept(param);
            var actual = read(lossless(image), param);
            assertEquals(expected.getWidth(), actual.getWidth());
            assertEquals(expected.getHeight(), actual.getHeight());
            assertArrayEquals(pixels(expected), pixels(actual));
        }

        @Test
        @DisplayName("a destination image is filled and returned")
        void destination() throws IOException {
            var target = new BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB);
            var param = new ImageReadParam();
            param.setDestination(target);
            param.setDestinationOffset(new Point(10, 10));
            var image = image(true);
            assertSame(target, read(lossless(image), param));
            assertEquals(image.getRGB(3, 4), target.getRGB(13, 14));
            assertEquals(0, target.getRGB(5, 5));
        }

        @Test
        @DisplayName("the metadata reports the format, alpha and orientation")
        void metadata() throws IOException {
            var data = lossless(image(true));
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                var reader = ImageIO.getImageReaders(input).next();
                reader.setInput(input);
                var metadata = reader.getImageMetadata(0);
                var root = (IIOMetadataNode) metadata.getAsTree(SmaltImageMetadataFormat.NAME);
                assertEquals("webp", root.getAttribute("format"));
                assertEquals("97", root.getAttribute("width"));
                assertEquals("true", root.getAttribute("hasAlpha"));
                assertEquals("1", root.getAttribute("orientation"));
                assertEquals("false", root.getAttribute("animated"));
                var standard = (IIOMetadataNode) metadata.getAsTree(IIOMetadataFormatImpl.standardMetadataFormatName);
                var orientation = (IIOMetadataNode) standard.getElementsByTagName("ImageOrientation").item(0);
                assertEquals("Normal", orientation.getAttribute("value"));
                var alpha = (IIOMetadataNode) standard.getElementsByTagName("Alpha").item(0);
                assertEquals("nonpremultiplied", alpha.getAttribute("value"));
                assertNotNull(metadata.getMetadataFormat(SmaltImageMetadataFormat.NAME));
                assertEquals(1, reader.getNumImages(true));
                assertEquals(61, reader.getHeight(0));
            }
        }
    }

    @Test
    @DisplayName("the EXIF orientation is reported, not applied")
    void orientation() throws IOException {
        var image = image(false);
        // a little-endian TIFF block with one IFD entry: Orientation (0x0112), SHORT, 6
        byte[] exif = {'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0};
        var data = WebpContainer.withExif(lossless(image), exif);
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            var reader = ImageIO.getImageReaders(input).next();
            reader.setInput(input);
            var metadata = (SmaltImageMetadata) reader.getImageMetadata(0);
            assertEquals(Orientation.ROTATE_90, metadata.orientation());
            assertArrayEquals(exif, metadata.exif().orElseThrow());
            var root = (IIOMetadataNode) metadata.getAsTree(SmaltImageMetadataFormat.NAME);
            assertEquals("6", root.getAttribute("orientation"));
            assertInstanceOf(byte[].class, ((IIOMetadataNode) root.getElementsByTagName("Exif").item(0)).getUserObject());
            var standard = (IIOMetadataNode) metadata.getAsTree(IIOMetadataFormatImpl.standardMetadataFormatName);
            var orientation = (IIOMetadataNode) standard.getElementsByTagName("ImageOrientation").item(0);
            assertEquals("Rotate270", orientation.getAttribute("value"));
            var decoded = reader.read(0);
            assertEquals(97, decoded.getWidth());
            assertArrayEquals(pixels(image), pixels(decoded));
        }
    }

    @Nested
    @DisplayName("animations")
    class Animations {
        static List<int[]> frames(byte[] data) {
            var result = new ArrayList<int[]>();
            try (var animation = Images.openAnimation(MediaSource.of(data))) {
                for (var frame = animation.read(); frame != null; frame = animation.read()) {
                    result.add(pixels((RgbaPicture) frame.picture()));
                }
            }
            return result;
        }

        static void checkFrames(byte[] data, String format) throws IOException {
            var expected = frames(data);
            assertTrue(expected.size() > 1);
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                ImageReader reader = ImageIO.getImageReaders(input).next();
                assertInstanceOf(SmaltImageReader.class, reader);
                reader.setInput(input);
                assertEquals(expected.size(), reader.getNumImages(true));
                for (var i = expected.size() - 1; i >= 0; i--) {
                    assertArrayEquals(expected.get(i), pixels(reader.read(i)), "frame " + i);
                }
                var metadata = (IIOMetadataNode) reader.getImageMetadata(1).getAsTree(SmaltImageMetadataFormat.NAME);
                assertEquals(format, metadata.getAttribute("format"));
                assertEquals("true", metadata.getAttribute("animated"));
                var animation = (IIOMetadataNode) metadata.getElementsByTagName("Animation").item(0);
                assertEquals("1", animation.getAttribute("frameIndex"));
                assertEquals("100", animation.getAttribute("delayMillis"));
            }
        }

        @Test
        @DisplayName("an animated WebP reads as one image per composited frame")
        void webp() throws IOException {
            var fixture = Fixtures.ffmpeg("webp", List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10:duration=0.4"),
                    List.of("-c:v", "libwebp_anim", "-lossless", "1", "-loop", "0"));
            checkFrames(Fixtures.read(fixture), "webp");
        }

        @Test
        @DisplayName("an APNG reads through Smalt while a still PNG stays with the JDK")
        void apng() throws IOException {
            var fixture = Fixtures.ffmpeg("png", List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=10:duration=0.4"),
                    List.of("-plays", "0", "-f", "apng"));
            checkFrames(Fixtures.read(fixture), "png");
            var still = new ByteArrayOutputStream();
            ImageIO.write(image(false), "png", still);
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(still.toByteArray()))) {
                assertFalse(ImageIO.getImageReaders(input).next() instanceof SmaltImageReader);
            }
        }
    }
}
