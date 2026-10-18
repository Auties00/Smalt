package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the TIFF and ICO plug-ins: the {@code smalt-tiff} reader and writer leave the JDK's
 * {@code tiff} plug-ins in charge of that name and of {@code ImageIO.read}, the reader exposes
 * every page of a multi-page file, and icons and cursors are read and written under {@code ico}.
 */
@DisplayName("ImageIO TIFF and ICO plug-ins")
class ImageIoTiffIcoTest {
    private static BufferedImage image(int width, int height, int seed) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(20 * seed % 256, 120, 200, 255));
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(new Color(250, 10, 40, 128));
        graphics.fillRect(1, 1, width / 2, height / 2);
        graphics.dispose();
        return image;
    }

    private static void assertSamePixels(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        for (var y = 0; y < expected.getHeight(); y++) {
            for (var x = 0; x < expected.getWidth(); x++) {
                assertEquals(Integer.toHexString(expected.getRGB(x, y)), Integer.toHexString(actual.getRGB(x, y)), x + "," + y);
            }
        }
    }

    /** Writes a three-page TIFF with the JDK's own writer. */
    private static byte[] jdkMultiPage() throws IOException {
        var writer = ImageIO.getImageWritersByFormatName("tiff").next();
        var bytes = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            writer.writeToSequence(new IIOImage(image(30, 20, 1), null, null), null);
            writer.writeToSequence(new IIOImage(image(12, 40, 2), null, null), null);
            writer.writeToSequence(new IIOImage(image(7, 7, 3), null, null), null);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    @Test
    @DisplayName("the smalt-tiff reader reads every page; ImageIO.read keeps the JDK reader")
    void tiffPages() throws IOException {
        var file = jdkMultiPage();
        var reader = ImageIO.getImageReadersByFormatName("smalt-tiff").next();
        assertInstanceOf(SmaltImageReader.class, reader);
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
            reader.setInput(input);
            assertEquals(3, reader.getNumImages(true));
            assertEquals(12, reader.getWidth(1));
            assertEquals(40, reader.getHeight(1));
            assertSamePixels(image(12, 40, 2), reader.read(1));
            assertSamePixels(image(30, 20, 1), reader.read(0));
            assertSamePixels(image(7, 7, 3), reader.read(2));
            assertNotNull(reader.getImageMetadata(2));
            assertThrows(IndexOutOfBoundsException.class, () -> reader.read(3));
        } finally {
            reader.dispose();
        }
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
            var first = ImageIO.getImageReaders(input).next();
            assertFalse(first instanceof SmaltImageReader, first.getClass().getName());
        }
        assertFalse(ImageIO.getImageReadersBySuffix("tif").next() instanceof SmaltImageReader);
    }

    @Test
    @DisplayName("the smalt-tiff writer writes TIFF; the tiff name keeps the JDK writer")
    void tiffWriter() throws IOException {
        var source = image(33, 17, 4);
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "smalt-tiff", bytes));
        var data = bytes.toByteArray();
        assertEquals(ImageFormat.TIFF, Images.detect(MediaSource.of(data)).orElseThrow());
        assertSamePixels(source, ImageIO.read(new ByteArrayInputStream(data)));
        assertFalse(ImageIO.getImageWritersByFormatName("tiff").next() instanceof SmaltImageWriter);
    }

    @Test
    @DisplayName("icons and cursors are read and written under ico")
    void icons() throws IOException {
        var source = image(48, 48, 5);
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "ico", bytes));
        var data = bytes.toByteArray();
        assertEquals(ImageFormat.ICO, Images.detect(MediaSource.of(data)).orElseThrow());
        assertSamePixels(source, ImageIO.read(new ByteArrayInputStream(data)));
        assertInstanceOf(SmaltImageReader.class, ImageIO.getImageReadersBySuffix("cur").next());
        assertInstanceOf(SmaltImageReader.class, ImageIO.getImageReadersByMIMEType("image/x-icon").next());
    }
}
