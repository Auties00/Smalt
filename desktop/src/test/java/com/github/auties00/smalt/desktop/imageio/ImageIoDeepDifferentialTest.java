package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Compares the 16-bit reads of the Smalt reader with those of the JDK's PNG and TIFF readers, used as oracles:
 * the same band layout and the same samples, sample for sample, and checks that the 8-bit types still give the
 * decoders' default 8-bit output.
 */
@DisplayName("ImageIO 16-bit reads against the JDK's readers")
class ImageIoDeepDifferentialTest {
    private static final Tolerance EXACT = Tolerance.exact();

    static SmaltImageReader smaltReader() {
        return (SmaltImageReader) new SmaltImageReaderSpi().createReaderInstance(null);
    }

    static ImageReader jdkReader(String format) {
        var readers = ImageIO.getImageReadersByFormatName(format);
        while (readers.hasNext()) {
            var reader = readers.next();
            if (!(reader instanceof SmaltImageReader)) {
                return reader;
            }
        }
        throw new AssertionError("no JDK reader of " + format);
    }

    static BufferedImage read(ImageReader reader, byte[] file) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
            reader.setInput(input);
            return reader.read(0);
        } finally {
            reader.dispose();
        }
    }

    static int[] samples(Raster raster) {
        return raster.getPixels(raster.getMinX(), raster.getMinY(), raster.getWidth(), raster.getHeight(),
                (int[]) null);
    }

    static void assertSameSamples(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        assertEquals(expected.getRaster().getNumBands(), actual.getRaster().getNumBands(), "bands");
        assertEquals(expected.getColorModel().hasAlpha(), actual.getColorModel().hasAlpha(), "alpha");
        assertEquals(DataBuffer.TYPE_USHORT, actual.getRaster().getTransferType(), "transfer type");
        EXACT.assertWithin("samples", samples(expected.getRaster()), samples(actual.getRaster()));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"rgb48be", "rgba64be", "gray16be", "ya16be"})
    @DisplayName("a 16-bit PNG reads with the JDK PNG reader's layout and samples")
    void png(String pixelFormat) throws IOException {
        var file = Fixtures.read(Fixtures.testImage("png", 61, 37, List.of("-pix_fmt", pixelFormat)));
        var jdk = read(jdkReader("png"), file);
        var smalt = read(smaltReader(), file);
        assertSameSamples(jdk, smalt);
        if (pixelFormat.equals("gray16be")) {
            assertEquals(BufferedImage.TYPE_USHORT_GRAY, smalt.getType());
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"rgb48le", "rgba64le", "gray16le"})
    @DisplayName("a 16-bit TIFF reads through smalt-tiff with the JDK TIFF reader's samples")
    void tiff(String pixelFormat) throws IOException {
        var file = Fixtures.read(Fixtures.testImage("tif", 45, 29, List.of("-pix_fmt", pixelFormat)));
        var jdk = read(jdkReader("tiff"), file);
        var smalt = read(ImageIO.getImageReadersByFormatName("smalt-tiff").next(), file);
        assertSameSamples(jdk, smalt);
    }

    @Test
    @DisplayName("the 8-bit types of a 16-bit image give the decoder's default 8-bit pixels")
    void eightBit() throws IOException {
        var file = Fixtures.read(Fixtures.testImage("png", 40, 30, List.of("-pix_fmt", "rgba64be")));
        var reader = smaltReader();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
            reader.setInput(input);
            var types = reader.getImageTypes(0);
            assertEquals(DataBuffer.TYPE_USHORT, types.next().getSampleModel().getTransferType());
            assertEquals(BufferedImage.TYPE_INT_ARGB, types.next().getBufferedImageType());
            var param = reader.getDefaultReadParam();
            param.setDestinationType(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB));
            var image = reader.read(0, param);
            var expected = ImageOps.toRgba(Images.decode(MediaSource.of(file)).picture());
            for (var y = 0; y < 30; y++) {
                for (var x = 0; x < 40; x++) {
                    var at = y * expected.stride() + x * 4;
                    var p = expected.pixels();
                    var argb = (p[at + 3] & 0xFF) << 24 | (p[at] & 0xFF) << 16 | (p[at + 1] & 0xFF) << 8 | p[at + 2] & 0xFF;
                    assertEquals(argb, image.getRGB(x, y), x + "," + y);
                }
            }
        } finally {
            reader.dispose();
        }
    }

    @Test
    @DisplayName("a region and a subsampled read of a 16-bit image select the full read's samples")
    void regionAndSubsampling() throws IOException {
        var file = Fixtures.read(Fixtures.testImage("png", 50, 40, List.of("-pix_fmt", "rgb48be")));
        var full = read(smaltReader(), file);
        var reader = smaltReader();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
            reader.setInput(input);
            var param = reader.getDefaultReadParam();
            param.setSourceRegion(new Rectangle(7, 5, 20, 11));
            var region = reader.read(0, param);
            EXACT.assertWithin("region", samples(full.getRaster().createChild(7, 5, 20, 11, 0, 0, null)),
                    samples(region.getRaster()));
            param = reader.getDefaultReadParam();
            param.setSourceSubsampling(3, 2, 1, 1);
            var subsampled = reader.read(0, param);
            assertEquals(17, subsampled.getWidth());
            assertEquals(20, subsampled.getHeight());
            for (var y = 0; y < 20; y++) {
                for (var x = 0; x < 17; x++) {
                    for (var b = 0; b < 3; b++) {
                        assertEquals(full.getRaster().getSample(1 + 3 * x, 1 + 2 * y, b),
                                subsampled.getRaster().getSample(x, y, b), x + "," + y + " band " + b);
                    }
                }
            }
        } finally {
            reader.dispose();
        }
        assertNotNull(full);
    }

    @Test
    @DisplayName("an 8-bit image written into a 16-bit destination widens each sample v to v * 257")
    void widen() throws IOException {
        var file = Fixtures.read(Fixtures.testImage("png", 20, 10, List.of("-pix_fmt", "rgb24")));
        var reader = smaltReader();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
            reader.setInput(input);
            var eight = reader.read(0);
            var param = reader.getDefaultReadParam();
            var deep = ImageTypeSpecifier.createInterleaved(ColorSpace.getInstance(ColorSpace.CS_sRGB),
                    new int[]{0, 1, 2}, DataBuffer.TYPE_USHORT, false, false).createBufferedImage(20, 10);
            param.setDestination(deep);
            reader.read(0, param);
            for (var y = 0; y < 10; y++) {
                for (var x = 0; x < 20; x++) {
                    var rgb = eight.getRGB(x, y);
                    assertEquals((rgb >> 16 & 0xFF) * 257, deep.getRaster().getSample(x, y, 0));
                    assertEquals((rgb >> 8 & 0xFF) * 257, deep.getRaster().getSample(x, y, 1));
                    assertEquals((rgb & 0xFF) * 257, deep.getRaster().getSample(x, y, 2));
                }
            }
        } finally {
            reader.dispose();
        }
    }
}
