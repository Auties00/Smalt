package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers raster and 16-bit image writes: the samples written to a lossless TIFF ({@code smalt-tiff}) read back
 * exactly through Smalt's decoder and through the JDK's TIFF reader.
 */
@DisplayName("ImageIO raster and 16-bit writes")
class ImageIoRasterWriteTest {
    static byte[] write(IIOImage image, javax.imageio.ImageWriteParam param) throws IOException {
        return write("smalt-tiff", image, param);
    }

    static byte[] write(String format, IIOImage image, javax.imageio.ImageWriteParam param) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName(format).next();
        var out = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(output);
            assertTrue(writer.canWriteRasters());
            writer.write(null, image, param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    static WritableRaster raster(int dataType, int bands, int width, int height) {
        var raster = Raster.createInterleavedRaster(dataType, width, height, bands, new Point(3, 2));
        var maximum = dataType == DataBuffer.TYPE_BYTE ? 255 : 65535;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var b = 0; b < bands; b++) {
                    raster.setSample(3 + x, 2 + y, b, (x * 977 + y * 131 + b * 4001) % (maximum + 1));
                }
            }
        }
        return raster;
    }

    static Rgba16Picture deep(byte[] file) {
        return ImageOps.toRgba16(Images.decode(MediaSource.of(file), ImageDecoderOptions.builder().highBitDepth(true)
                .build()).picture());
    }

    /** The number of channels the IHDR color type of a PNG declares. */
    static int deepBands(byte[] png) {
        return switch (png[25]) {
            case 0 -> 1;
            case 4 -> 2;
            case 2 -> 3;
            case 6 -> 4;
            default -> -1;
        };
    }

    /** The RGBA channel of a raster band: gray fills R, G and B, the last band of 2 or 4 is alpha. */
    static int channelOf(int bands, int channel) {
        if (bands <= 2) {
            return channel < 3 ? 0 : 1;
        }
        return channel;
    }

    @ParameterizedTest(name = "{0} bands, 16-bit {1}")
    @CsvSource({"1, false", "2, false", "3, false", "4, false", "1, true", "2, true", "3, true", "4, true"})
    @DisplayName("a raster of gray, gray and alpha, RGB or RGBA samples is written exactly")
    void rasters(int bands, boolean sixteen) throws IOException {
        var dataType = sixteen ? DataBuffer.TYPE_USHORT : DataBuffer.TYPE_BYTE;
        var raster = raster(dataType, bands, 23, 17);
        var file = write(new IIOImage(raster, null, null), null);
        var picture = deep(file);
        assertEquals(23, picture.width());
        assertEquals(bands == 2 || bands == 4, picture.hasAlpha());
        for (var y = 0; y < 17; y++) {
            for (var x = 0; x < 23; x++) {
                for (var channel = 0; channel < (picture.hasAlpha() ? 4 : 3); channel++) {
                    var sample = raster.getSample(3 + x, 2 + y, channelOf(bands, channel));
                    var expected = sixteen ? sample : sample * 257;
                    assertEquals(expected, picture.pixels()[y * picture.stride() + x * 4 + channel] & 0xFFFF,
                            x + "," + y + " channel " + channel);
                }
            }
        }
        // the JDK's TIFF reader declines 16-bit samples under the horizontal predictor, so the JDK oracle reads
        // the same raster written as a PNG
        var png = write("apng", new IIOImage(raster, null, null), null);
        var jdk = ImageIoDeepDifferentialTest.read(ImageIoDeepDifferentialTest.jdkReader("png"), png);
        if (sixteen) {
            assertEquals(bands, deepBands(png), "PNG color type");
        }
        for (var y = 0; y < 17; y++) {
            for (var x = 0; x < 23; x++) {
                if (jdk.getRaster().getNumBands() == bands) {
                    for (var b = 0; b < bands; b++) {
                        assertEquals(raster.getSample(3 + x, 2 + y, b), jdk.getRaster().getSample(x, y, b));
                    }
                } else {
                    // an 8-bit PNG of few colors may be written with a palette
                    var argb = jdk.getRGB(x, y);
                    for (var channel = 0; channel < 4; channel++) {
                        var expected = channel == 3 && bands % 2 == 1 ? 255
                                : raster.getSample(3 + x, 2 + y, channelOf(bands, channel));
                        var shift = channel == 3 ? 24 : 16 - 8 * channel;
                        assertEquals(expected, argb >>> shift & 0xFF, x + "," + y + " channel " + channel);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("12-bit samples are rescaled to 16 bits and float samples read as 0 to 1")
    void rescaled() throws IOException {
        var model = new SinglePixelPackedSampleModel(DataBuffer.TYPE_USHORT, 4, 3, new int[]{0xFFF});
        var packed = Raster.createWritableRaster(model, null);
        for (var i = 0; i < 12; i++) {
            packed.setSample(i % 4, i / 4, 0, i * 372);
        }
        var picture = deep(write(new IIOImage(packed, null, null), null));
        for (var i = 0; i < 12; i++) {
            var expected = (int) ((i * 372L * 65535 + 2047) / 4095);
            assertEquals(expected, picture.pixels()[(i / 4) * picture.stride() + (i % 4) * 4] & 0xFFFF, "sample " + i);
        }
        var floating = Raster.createWritableRaster(new java.awt.image.BandedSampleModel(DataBuffer.TYPE_FLOAT, 2, 1, 1),
                null);
        floating.setSample(0, 0, 0, 0.5f);
        floating.setSample(1, 0, 0, 2f);
        var fromFloats = deep(write(new IIOImage(floating, null, null), null));
        assertEquals(32768, fromFloats.pixels()[0] & 0xFFFF);
        assertEquals(65535, fromFloats.pixels()[4] & 0xFFFF);
    }

    @Test
    @DisplayName("a source region and subsampling select raster samples")
    void region() throws IOException {
        var raster = raster(DataBuffer.TYPE_BYTE, 3, 20, 10);
        var writer = ImageIO.getImageWritersByFormatName("smalt-tiff").next();
        var param = writer.getDefaultWriteParam();
        writer.dispose();
        param.setSourceRegion(new Rectangle(5, 4, 10, 6));
        param.setSourceSubsampling(2, 3, 0, 0);
        var picture = deep(write(new IIOImage(raster, null, null), param));
        assertEquals(5, picture.width());
        assertEquals(2, picture.height());
        for (var y = 0; y < 2; y++) {
            for (var x = 0; x < 5; x++) {
                assertEquals(raster.getSample(5 + 2 * x, 4 + 3 * y, 1) * 257,
                        picture.pixels()[y * picture.stride() + x * 4 + 1] & 0xFFFF);
            }
        }
    }

    @Test
    @DisplayName("rasters of signed shorts or more than four bands are rejected")
    void rejected() {
        var signed = Raster.createWritableRaster(new java.awt.image.PixelInterleavedSampleModel(DataBuffer.TYPE_SHORT,
                2, 2, 1, 2, new int[]{0}), null);
        assertThrows(IllegalArgumentException.class, () -> write(new IIOImage(signed, null, null), null));
        var five = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 2, 2, 5, null);
        assertThrows(IllegalArgumentException.class, () -> write(new IIOImage(five, null, null), null));
    }

    @Test
    @DisplayName("a 16-bit gray image keeps its 16 bits in TIFF and in PNG, as the JDK PNG reader reads them")
    void sixteenBitImage() throws IOException {
        var image = new BufferedImage(31, 9, BufferedImage.TYPE_USHORT_GRAY);
        for (var y = 0; y < 9; y++) {
            for (var x = 0; x < 31; x++) {
                image.getRaster().setSample(x, y, 0, x * 2111 + y * 7);
            }
        }
        var tiff = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "smalt-tiff", tiff));
        var png = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "apng", png));
        var jdk = ImageIoDeepDifferentialTest.read(ImageIoDeepDifferentialTest.jdkReader("png"), png.toByteArray());
        var smalt = ImageIoDeepDifferentialTest.read(ImageIO.getImageReadersByFormatName("smalt-tiff").next(),
                tiff.toByteArray());
        assertEquals(BufferedImage.TYPE_USHORT_GRAY, smalt.getType());
        for (var y = 0; y < 9; y++) {
            for (var x = 0; x < 31; x++) {
                assertEquals(image.getRaster().getSample(x, y, 0), jdk.getRaster().getSample(x, y, 0));
                assertEquals(image.getRaster().getSample(x, y, 0), smalt.getRaster().getSample(x, y, 0));
            }
        }
    }
}
