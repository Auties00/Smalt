package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.tiff.TiffEncoder;
import com.github.auties00.smalt.image.tiff.TiffEncoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Covers source regions of the image reader: a read of a source region without subsampling decodes only that
 * region, with the pixels of the whole image, at destination offsets too, and later whole reads still decode the
 * whole image.
 */
@DisplayName("ImageIO source regions")
class ImageIoRegionTest {
    private static byte[] tiledTiff() {
        var picture = RgbaPicture.allocate(300, 200, true);
        new Random(1).nextBytes(picture.pixels());
        return TiffEncoder.encode(picture, TiffEncoderOptions.builder().tiles(64, 64).build());
    }

    private static void assertRegion(BufferedImage whole, BufferedImage actual, Rectangle region, Point offset) {
        for (var y = 0; y < region.height; y++) {
            for (var x = 0; x < region.width; x++) {
                assertEquals(Integer.toHexString(whole.getRGB(region.x + x, region.y + y)),
                        Integer.toHexString(actual.getRGB(offset.x + x, offset.y + y)), x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("a source region reads the pixels of the whole image, at destination offsets too")
    void sourceRegions() throws IOException {
        var file = tiledTiff();
        var reader = ImageIO.getImageReadersByFormatName(SmaltImageIo.TIFF).next();
        assertInstanceOf(SmaltImageReader.class, reader);
        try {
            for (var region : List.of(new Rectangle(70, 30, 100, 90), new Rectangle(0, 0, 1, 1),
                    new Rectangle(250, 150, 80, 80), new Rectangle(299, 0, 1, 200))) {
                try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
                    reader.setInput(input);
                    var param = reader.getDefaultReadParam();
                    param.setSourceRegion(region);
                    var actual = reader.read(0, param);
                    var clipped = region.intersection(new Rectangle(0, 0, 300, 200));
                    assertEquals(clipped.width, actual.getWidth());
                    assertEquals(clipped.height, actual.getHeight());
                    var whole = reader.read(0);
                    assertEquals(300, whole.getWidth());
                    assertRegion(whole, actual, clipped, new Point(0, 0));
                }
                try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
                    reader.setInput(input);
                    var param = reader.getDefaultReadParam();
                    param.setSourceRegion(region);
                    param.setDestinationOffset(new Point(5, 3));
                    var actual = reader.read(0, param);
                    var clipped = region.intersection(new Rectangle(0, 0, 300, 200));
                    try (var again = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
                        var fresh = ImageIO.getImageReadersByFormatName(SmaltImageIo.TIFF).next();
                        fresh.setInput(again);
                        assertRegion(fresh.read(0), actual, clipped, new Point(5, 3));
                        fresh.dispose();
                    }
                }
            }
        } finally {
            reader.dispose();
        }
    }
}
