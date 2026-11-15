package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.bmp.BmpEncoder;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the BMP plug-ins: the {@code smalt-bmp} writer writes files the JDK's reader reads back, the
 * {@code smalt-bmp} reader reads every bitmap of an OS/2 bitmap array, and the JDK's plug-ins keep the {@code bmp}
 * name, suffix and {@code ImageIO.read}.
 */
@DisplayName("ImageIO BMP plug-ins")
class ImageIoBmpTest {
    private static BufferedImage image(int width, int height, int seed, boolean translucent) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(20 * seed % 256, 120, 200, 255));
        graphics.fillRect(0, 0, width, height);
        graphics.setComposite(AlphaComposite.Src);
        graphics.setColor(new Color(250, 10, 40, translucent ? 128 : 255));
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

    private static RgbaPicture picture(int width, int height, int seed) {
        var pixels = new byte[width * height * 4];
        for (var i = 0; i < width * height; i++) {
            pixels[i * 4] = (byte) (i * 7 + seed);
            pixels[i * 4 + 1] = (byte) (i * 13);
            pixels[i * 4 + 2] = (byte) (seed * 50);
            pixels[i * 4 + 3] = (byte) 0xFF;
        }
        return RgbaPicture.of(width, height, width * 4, pixels, false);
    }

    // links two bitmaps into an OS/2 bitmap array, moving each bitmap's file-relative pixel data offset
    private static byte[] array(byte[] first, byte[] second) {
        var out = ByteBuffer.allocate(28 + first.length + second.length).order(ByteOrder.LITTLE_ENDIAN);
        var position = 0;
        for (var entry : new byte[][]{first, second}) {
            var copy = entry.clone();
            ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putInt(10, ByteBuffer.wrap(copy)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt(10) + position + 14);
            out.put((byte) 'B').put((byte) 'A').putInt(14 + 14 + 40);
            out.putInt(entry == first ? 14 + first.length : 0).putShort((short) 0).putShort((short) 0);
            out.put(copy);
            position += 14 + entry.length;
        }
        return out.array();
    }

    @Test
    @DisplayName("the smalt-bmp writer writes BMP the JDK reads back; the bmp name keeps the JDK writer")
    void writer() throws IOException {
        for (var translucent : new boolean[]{false, true}) {
            var source = image(33, 17, 4, translucent);
            var bytes = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(source, "smalt-bmp", bytes));
            var data = bytes.toByteArray();
            assertEquals(ImageFormat.BMP, Images.detect(MediaSource.of(data)).orElseThrow());
            assertEquals(translucent ? 32 : 1, data[28], "the smallest lossless layout");
            assertSamePixels(source, ImageIO.read(new ByteArrayInputStream(data)));
        }
        assertFalse(ImageIO.getImageWritersByFormatName("bmp").next() instanceof SmaltImageWriter);
        assertFalse(ImageIO.getImageWritersBySuffix("bmp").next() instanceof SmaltImageWriter);
        assertInstanceOf(SmaltImageWriter.class, ImageIO.getImageWritersByFormatName(SmaltImageIo.BMP).next());
    }

    @Test
    @DisplayName("the smalt-bmp reader reads every bitmap of an array; ImageIO.read keeps the JDK reader")
    void reader() throws IOException {
        var small = picture(9, 5, 1);
        var large = picture(20, 11, 2);
        var file = array(BmpEncoder.encode(small), BmpEncoder.encode(large));
        var reader = ImageIO.getImageReadersByFormatName(SmaltImageIo.BMP).next();
        assertInstanceOf(SmaltImageReader.class, reader);
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
            reader.setInput(input);
            assertEquals(2, reader.getNumImages(true));
            assertEquals(9, reader.getWidth(0));
            assertEquals(11, reader.getHeight(1));
            var second = reader.read(1);
            assertEquals(new Color(large.pixels()[0] & 0xFF, large.pixels()[1] & 0xFF, large.pixels()[2] & 0xFF).getRGB(),
                    second.getRGB(0, 0));
        } finally {
            reader.dispose();
        }
        var plain = BmpEncoder.encode(large);
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(plain))) {
            var first = ImageIO.getImageReaders(input).next();
            assertFalse(first instanceof SmaltImageReader, first.getClass().getName());
        }
        assertFalse(ImageIO.getImageReadersBySuffix("bmp").next() instanceof SmaltImageReader);
    }
}
