package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.avif.AvifEncoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Covers AVIF through ImageIO once {@code smalt-video} is present: the reader is found by name,
 * suffix and MIME type and reads the pixels the registry decodes, alpha included, and the writer
 * writes files that read back close to the source with exact alpha.
 */
@DisplayName("ImageIO AVIF reading and writing")
class ImageIoAvifTest {
    @Test
    @DisplayName("finds the AVIF reader and reads the registry's pixels with alpha")
    void read() throws IOException {
        for (var readers : List.of(ImageIO.getImageReadersByFormatName("avif"), ImageIO.getImageReadersBySuffix("avif"),
                ImageIO.getImageReadersByMIMEType("image/avif"))) {
            assertInstanceOf(SmaltImageReader.class, readers.next());
        }
        var file = Fixtures.ffmpeg("avif", List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=1,format=yuva420p"),
                List.of("-frames:v", "1", "-filter_complex", "[0]split[c][a];[a]alphaextract[al]", "-map", "[c]", "-map",
                        "[al]", "-c:v", "libaom-av1", "-still-picture", "1", "-cpu-used", "8"));
        var image = ImageIO.read(file.toFile());
        assertNotNull(image);
        assertEquals(96, image.getWidth());
        assertEquals(64, image.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
        var expected = ImageOps.toRgba(Images.decode(MediaSource.of(Fixtures.read(file))).picture());
        assertTrue(expected.hasAlpha());
        for (var y = 0; y < 64; y++) {
            for (var x = 0; x < 96; x++) {
                var at = y * expected.stride() + x * 4;
                var p = expected.pixels();
                var argb = ((p[at + 3] & 0xFF) << 24) | ((p[at] & 0xFF) << 16) | ((p[at + 1] & 0xFF) << 8) | (p[at + 2] & 0xFF);
                assertEquals(argb, image.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("describes a 10-bit monochrome AVIF as gray in its metadata, as its image type does")
    void monochromeMetadata() throws IOException {
        var luma = new short[64 * 32];
        for (var i = 0; i < luma.length; i++) {
            luma[i] = (short) (64 + (i % 64) * 12);
        }
        var picture = Yuv16Picture.builder().size(64, 32).chromaFormat(ChromaFormat.MONOCHROME).bitDepth(10)
                .color(ColorInfo.BT709_LIMITED).luma(luma, 64).build();
        var bytes = AvifEncoder.encode(picture);
        var decoded = Images.decode(MediaSource.of(bytes), ImageDecoderOptions.builder().highBitDepth(true).build());
        assertEquals(ChromaFormat.MONOCHROME, assertInstanceOf(Yuv16Picture.class, decoded.picture()).chromaFormat());
        assertEquals(BufferedImage.TYPE_BYTE_GRAY, BufferedImages.imageTypeOf(decoded.picture()));
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var reader = ImageIO.getImageReaders(input).next();
            reader.setInput(input);
            var tree = (IIOMetadataNode) reader.getImageMetadata(0)
                    .getAsTree(IIOMetadataFormatImpl.standardMetadataFormatName);
            var space = (IIOMetadataNode) tree.getElementsByTagName("ColorSpaceType").item(0);
            assertEquals("GRAY", space.getAttribute("name"));
            assertEquals(BufferedImage.TYPE_BYTE_GRAY, reader.read(0).getType());
            reader.dispose();
        }
    }

    @Test
    @DisplayName("writes an AVIF that reads back with its alpha")
    void write() throws IOException {
        var source = new BufferedImage(80, 48, BufferedImage.TYPE_INT_ARGB);
        for (var y = 0; y < 48; y++) {
            for (var x = 0; x < 80; x++) {
                source.setRGB(x, y, ((x * 3) << 24) | ((x * 3) << 16) | ((y * 5) << 8) | ((x + y) * 2));
            }
        }
        var out = new java.io.ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "avif", out));
        var image = ImageIO.read(new java.io.ByteArrayInputStream(out.toByteArray()));
        assertNotNull(image);
        long sse = 0;
        for (var y = 0; y < 48; y++) {
            for (var x = 0; x < 80; x++) {
                var a = source.getRGB(x, y);
                var b = image.getRGB(x, y);
                assertEquals(a >>> 24, b >>> 24, "alpha " + x + "," + y);
                for (var shift = 0; shift < 24; shift += 8) {
                    var d = ((a >> shift) & 0xFF) - ((b >> shift) & 0xFF);
                    sse += (long) d * d;
                }
            }
        }
        var psnr = 10 * Math.log10(255.0 * 255 * 80 * 48 * 3 / Math.max(1, sse));
        assertTrue(psnr > 30, "psnr " + psnr);
    }
}
