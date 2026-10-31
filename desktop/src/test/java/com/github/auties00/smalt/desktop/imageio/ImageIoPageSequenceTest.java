package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers multi-page sequences: {@code writeToSequence} writes the pages of a TIFF and the entries of an icon,
 * which read back through Smalt's readers and, for the 8-bit TIFF pages, through the JDK's TIFF reader.
 */
@DisplayName("ImageIO multi-page sequences")
class ImageIoPageSequenceTest {
    static BufferedImage image(int width, int height, int type, int seed) {
        var image = new BufferedImage(width, height, type);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(30 * seed % 256, 90, 200, 255));
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(new Color(250, 10, 40, type == BufferedImage.TYPE_INT_ARGB ? 128 : 255));
        graphics.fillRect(1, 1, width / 2, height / 2);
        graphics.dispose();
        return image;
    }

    static BufferedImage deepGray(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                image.getRaster().setSample(x, y, 0, (x * 4099 + y * 61) & 0xFFFF);
            }
        }
        return image;
    }

    static byte[] sequence(String format, List<IIOImage> images) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName(format).next();
        var out = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(output);
            assertTrue(writer.canWriteSequence());
            writer.prepareWriteSequence(null);
            for (var image : images) {
                writer.writeToSequence(image, null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    static void assertSamePixels(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        for (var y = 0; y < expected.getHeight(); y++) {
            for (var x = 0; x < expected.getWidth(); x++) {
                assertEquals(Integer.toHexString(expected.getRGB(x, y)), Integer.toHexString(actual.getRGB(x, y)),
                        x + "," + y);
            }
        }
    }

    static void assertSameSamples(BufferedImage expected, BufferedImage actual) {
        for (var y = 0; y < expected.getHeight(); y++) {
            for (var x = 0; x < expected.getWidth(); x++) {
                assertEquals(expected.getRaster().getSample(x, y, 0), actual.getRaster().getSample(x, y, 0), x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("three TIFF pages of different sizes and depths read back through Smalt and the JDK")
    void tiffPages() throws IOException {
        var first = image(30, 20, BufferedImage.TYPE_INT_ARGB, 1);
        var second = deepGray(12, 40);
        var third = image(7, 7, BufferedImage.TYPE_INT_RGB, 3);
        var writer = (ImageWriter) ImageIO.getImageWritersByFormatName("smalt-tiff").next();
        var metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(third), null);
        writer.dispose();
        var root = new IIOMetadataNode(SmaltImageMetadataFormat.NAME);
        var density = new IIOMetadataNode("Density");
        density.setAttribute("unit", "dots_per_inch");
        density.setAttribute("horizontal", "300.0");
        density.setAttribute("vertical", "150.0");
        root.appendChild(density);
        metadata.mergeTree(SmaltImageMetadataFormat.NAME, root);
        var file = sequence("smalt-tiff", List.of(new IIOImage(first, null, null), new IIOImage(second, null, null),
                new IIOImage(third, null, metadata)));
        var smalt = ImageIO.getImageReadersByFormatName("smalt-tiff").next();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
            smalt.setInput(input);
            assertEquals(3, smalt.getNumImages(true));
            assertSamePixels(first, smalt.read(0));
            var deep = smalt.read(1);
            assertEquals(BufferedImage.TYPE_USHORT_GRAY, deep.getType());
            assertSameSamples(second, deep);
            assertSamePixels(third, smalt.read(2));
            var pageDensity = ((SmaltImageMetadata) smalt.getImageMetadata(2)).density().orElseThrow();
            assertEquals(PixelDensity.Unit.DOTS_PER_INCH, pageDensity.unit());
            assertEquals(300.0, pageDensity.horizontal(), 1e-6);
            assertEquals(150.0, pageDensity.vertical(), 1e-6);
            var firstDensity = ((SmaltImageMetadata) smalt.getImageMetadata(0)).density();
            assertTrue(firstDensity.isEmpty() || firstDensity.get().horizontal() != 300.0, "page 0 keeps its own density");
        } finally {
            smalt.dispose();
        }
        var jdk = ImageIoDeepDifferentialTest.jdkReader("tiff");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
            jdk.setInput(input);
            assertEquals(3, jdk.getNumImages(true));
            assertSamePixels(first, jdk.read(0));
            // the JDK's TIFF reader declines 16-bit samples under the horizontal predictor of the second page
            assertSamePixels(third, jdk.read(2));
        } finally {
            jdk.dispose();
        }
    }

    @Test
    @DisplayName("icon entries of several sizes read back as images of the icon")
    void iconEntries() throws IOException {
        var images = List.of(image(16, 16, BufferedImage.TYPE_INT_ARGB, 1), image(32, 32, BufferedImage.TYPE_INT_ARGB, 2),
                image(48, 48, BufferedImage.TYPE_INT_ARGB, 3));
        var file = sequence("ico", images.stream().map(image -> new IIOImage(image, null, null)).toList());
        try (var pages = Images.openPages(MediaSource.of(file))) {
            assertEquals(3, pages.pageCount());
        }
        var reader = ImageIO.getImageReadersByFormatName("ico").next();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
            reader.setInput(input);
            assertEquals(3, reader.getNumImages(true));
            for (var i = 0; i < 3; i++) {
                assertSamePixels(images.get(i), reader.read(i));
            }
        } finally {
            reader.dispose();
        }
    }

    @Test
    @DisplayName("formats without animations or pages refuse sequences, and an empty sequence fails")
    void refused() throws IOException {
        assertTrue(Images.isPageEncodable(ImageFormat.TIFF));
        var bmp = ImageIO.getImageWritersByFormatName("smalt-bmp").next();
        try (var output = ImageIO.createImageOutputStream(new ByteArrayOutputStream())) {
            bmp.setOutput(output);
            assertFalse(bmp.canWriteSequence());
            assertThrows(UnsupportedOperationException.class, () -> bmp.prepareWriteSequence(null));
        } finally {
            bmp.dispose();
        }
        var tiff = ImageIO.getImageWritersByFormatName("smalt-tiff").next();
        try (var output = ImageIO.createImageOutputStream(new ByteArrayOutputStream())) {
            tiff.setOutput(output);
            tiff.prepareWriteSequence(null);
            assertThrows(IIOException.class, tiff::endWriteSequence);
        } finally {
            tiff.dispose();
        }
    }
}
