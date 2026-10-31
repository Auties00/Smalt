package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers HEIC through ImageIO once {@code smalt-mpeg} is present: the reader is found by name,
 * suffix and MIME type, and reads the pixels the registry decodes, alpha included; the writer
 * writes a HEIC file that reads back with its alpha. The sample is
 * an 8-bit image with an alpha item from pillow_heif's test images (BSD-3-Clause).
 */
@DisplayName("ImageIO HEIC reading and writing")
class ImageIoHeicTest {
    @Test
    @DisplayName("finds the HEIC reader and reads the registry's pixels with alpha")
    void read() throws IOException {
        for (var readers : List.of(ImageIO.getImageReadersByFormatName("heic"), ImageIO.getImageReadersBySuffix("heic"),
                ImageIO.getImageReadersByMIMEType("image/heic"))) {
            assertInstanceOf(SmaltImageReader.class, readers.next());
        }
        var file = Conformance.fetch("heic-pillow-tests-images-heif-RGBA_8__128x128.heif",
                "https://raw.githubusercontent.com/bigcat88/pillow_heif/b16be1196dfa465a342d68894696e685ca3655cb/tests/images/heif/RGBA_8__128x128.heif",
                "df634be2fa51f3d9a244dc9c987d7179411814e7b171bc40273a4486181c559f");
        var image = ImageIO.read(file.toFile());
        assertNotNull(image);
        assertEquals(128, image.getWidth());
        assertEquals(128, image.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
        var expected = ImageOps.toRgba(Images.decode(MediaSource.of(Files.readAllBytes(file))).picture());
        assertTrue(expected.hasAlpha());
        for (var y = 0; y < 128; y++) {
            for (var x = 0; x < 128; x++) {
                var at = y * expected.stride() + x * 4;
                var p = expected.pixels();
                var argb = ((p[at + 3] & 0xFF) << 24) | ((p[at] & 0xFF) << 16) | ((p[at + 1] & 0xFF) << 8) | (p[at + 2] & 0xFF);
                assertEquals(argb, image.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("writes a HEIC that reads back with its alpha")
    void write() throws IOException {
        var source = new BufferedImage(80, 48, BufferedImage.TYPE_INT_ARGB);
        for (var y = 0; y < 48; y++) {
            for (var x = 0; x < 80; x++) {
                source.setRGB(x, y, ((x * 3) << 24) | ((x * 3) << 16) | ((y * 5) << 8) | ((x + y) * 2));
            }
        }
        var out = new java.io.ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "heic", out));
        assertEquals(com.github.auties00.smalt.image.ImageFormat.HEIC,
                Images.detect(MediaSource.of(out.toByteArray())).orElseThrow());
        var image = ImageIO.read(new java.io.ByteArrayInputStream(out.toByteArray()));
        assertNotNull(image);
        long sse = 0;
        var alphaError = 0;
        for (var y = 0; y < 48; y++) {
            for (var x = 0; x < 80; x++) {
                var a = source.getRGB(x, y);
                var b = image.getRGB(x, y);
                alphaError = Math.max(alphaError, Math.abs((a >>> 24) - (b >>> 24)));
                for (var shift = 0; shift < 24; shift += 8) {
                    var d = ((a >> shift) & 0xFF) - ((b >> shift) & 0xFF);
                    sse += (long) d * d;
                }
            }
        }
        assertTrue(alphaError <= 2, "alpha error " + alphaError);
        var psnr = 10 * Math.log10(255.0 * 255 * 80 * 48 * 3 / Math.max(1, sse));
        assertTrue(psnr > 30, "psnr " + psnr);
    }
}
