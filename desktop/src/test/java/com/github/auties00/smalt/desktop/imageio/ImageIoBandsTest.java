package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers source and destination band selection and raster reads of the Smalt reader, on 8-bit and 16-bit PNG
 * images whose full reads give the expected samples.
 */
@DisplayName("ImageIO band selection and raster reads")
class ImageIoBandsTest {
    // testsrc2 rounds odd sizes down to even ones
    private static final int WIDTH = 32;
    private static final int HEIGHT = 20;
    private static final byte[] RGBA = Fixtures.read(Fixtures.testImage("png", WIDTH, HEIGHT,
            List.of("-pix_fmt", "rgba")));
    private static final byte[] RGBA64 = Fixtures.read(Fixtures.testImage("png", WIDTH, HEIGHT,
            List.of("-pix_fmt", "rgba64be")));

    interface ReaderTask<T> {
        T run(SmaltImageReader reader) throws IOException;
    }

    static <T> T with(byte[] file, ReaderTask<T> task) throws IOException {
        var reader = ImageIoDeepDifferentialTest.smaltReader();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
            reader.setInput(input);
            return task.run(reader);
        } finally {
            reader.dispose();
        }
    }

    static int channel(BufferedImage image, int x, int y, int band) {
        var argb = image.getRGB(x, y);
        return switch (band) {
            case 0 -> argb >> 16 & 0xFF;
            case 1 -> argb >> 8 & 0xFF;
            case 2 -> argb & 0xFF;
            default -> argb >>> 24;
        };
    }

    @Test
    @DisplayName("selected source bands land in the selected destination bands, the others stay zero")
    void selection() throws IOException {
        var full = with(RGBA, reader -> reader.read(0));
        var selected = with(RGBA, reader -> {
            var param = reader.getDefaultReadParam();
            param.setSourceBands(new int[]{2, 0});
            param.setDestinationBands(new int[]{0, 3});
            return reader.read(0, param);
        });
        assertEquals(BufferedImage.TYPE_INT_ARGB, selected.getType());
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                assertEquals(channel(full, x, y, 2), selected.getRaster().getSample(x, y, 0));
                assertEquals(0, selected.getRaster().getSample(x, y, 1));
                assertEquals(0, selected.getRaster().getSample(x, y, 2));
                assertEquals(channel(full, x, y, 0), selected.getRaster().getSample(x, y, 3));
            }
        }
    }

    @Test
    @DisplayName("one band reads into a gray destination, and a 16-bit band is rescaled to 8 bits")
    void grayDestination() throws IOException {
        var deep = with(RGBA64, reader -> reader.read(0));
        var alpha = with(RGBA64, reader -> {
            var param = reader.getDefaultReadParam();
            param.setSourceBands(new int[]{3});
            param.setDestination(new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_GRAY));
            return reader.read(0, param);
        });
        assertEquals(BufferedImage.TYPE_BYTE_GRAY, alpha.getType());
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                var sample = deep.getRaster().getSample(x, y, 3);
                assertEquals((sample * 255 + 32767) / 65535, alpha.getRaster().getSample(x, y, 0), x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("band selection keeps the source region, subsampling and destination offset")
    void geometry() throws IOException {
        var full = with(RGBA, reader -> reader.read(0));
        var selected = with(RGBA, reader -> {
            var param = reader.getDefaultReadParam();
            param.setSourceRegion(new Rectangle(4, 3, 20, 15));
            param.setSourceSubsampling(2, 3, 1, 0);
            param.setSourceBands(new int[]{1});
            param.setDestinationBands(new int[]{1});
            var destination = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
            param.setDestination(destination);
            param.setDestinationOffset(new Point(5, 6));
            return reader.read(0, param);
        });
        for (var y = 0; y < 5; y++) {
            for (var x = 0; x < 10; x++) {
                assertEquals(channel(full, 5 + 2 * x, 3 + 3 * y, 1), selected.getRaster().getSample(5 + x, 6 + y, 1));
                assertEquals(0, selected.getRaster().getSample(5 + x, 6 + y, 0));
            }
        }
        assertEquals(0, selected.getRGB(4, 6) & 0xFFFFFF);
        assertEquals(0, selected.getRGB(15, 6) & 0xFFFFFF);
    }

    @Test
    @DisplayName("invalid band selections are rejected")
    void invalid() throws IOException {
        with(RGBA, reader -> {
            var param = reader.getDefaultReadParam();
            param.setSourceBands(new int[]{4});
            assertThrows(IllegalArgumentException.class, () -> reader.read(0, param));
            assertThrows(IllegalArgumentException.class, () -> reader.readRaster(0, param));
            var mismatch = reader.getDefaultReadParam();
            mismatch.setSourceBands(new int[]{0, 1});
            mismatch.setDestinationBands(new int[]{0});
            assertThrows(IllegalArgumentException.class, () -> reader.read(0, mismatch));
            return null;
        });
    }

    @Test
    @DisplayName("readRaster returns the first type's samples, 8 or 16 bits, with the selected bands")
    void rasters() throws IOException {
        var full = with(RGBA, reader -> reader.read(0));
        with(RGBA, reader -> {
            assertTrue(reader.canReadRaster());
            var raster = reader.readRaster(0, null);
            assertEquals(4, raster.getNumBands());
            assertEquals(0, raster.getMinX());
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    for (var b = 0; b < 4; b++) {
                        assertEquals(channel(full, x, y, b), raster.getSample(x, y, b));
                    }
                }
            }
            var param = reader.getDefaultReadParam();
            param.setSourceBands(new int[]{3, 1});
            param.setDestinationOffset(new Point(5, 7));
            param.setSourceRegion(new Rectangle(10, 2, 9, 8));
            var selected = reader.readRaster(0, param);
            assertEquals(2, selected.getNumBands());
            assertEquals(new Rectangle(5, 7, 9, 8), selected.getBounds());
            for (var y = 0; y < 8; y++) {
                for (var x = 0; x < 9; x++) {
                    assertEquals(channel(full, 10 + x, 2 + y, 3), selected.getSample(5 + x, 7 + y, 0));
                    assertEquals(channel(full, 10 + x, 2 + y, 1), selected.getSample(5 + x, 7 + y, 1));
                }
            }
            return null;
        });
        var deep = with(RGBA64, reader -> reader.read(0));
        with(RGBA64, reader -> {
            var raster = reader.readRaster(0, null);
            assertEquals(DataBuffer.TYPE_USHORT, raster.getTransferType());
            ImageIoDeepDifferentialTest.assertSameSamples(deep, new BufferedImage(deep.getColorModel(),
                    (WritableRaster) raster, false, null));
            return null;
        });
    }
}
