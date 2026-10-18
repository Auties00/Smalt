package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the APNG and GIF ImageIO writers: their format names leave the JDK's {@code png} and
 * {@code gif} writers in charge of those names, single images and sequences are written, and the
 * output decodes with the expected frames and format.
 */
@DisplayName("ImageIO APNG and GIF writers")
class ImageIoGifApngTest {
    private static List<BufferedImage> frames() {
        var frames = new ArrayList<BufferedImage>();
        for (var i = 0; i < 4; i++) {
            var frame = new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB);
            var graphics = frame.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 40, 30);
            graphics.setColor(new Color(30 + i * 50, 200 - i * 40, 90));
            graphics.fillRect(2 + i * 8, 4, 9, 12);
            graphics.dispose();
            frames.add(frame);
        }
        return frames;
    }

    private static byte[] sequence(String name) throws IOException {
        var writer = ImageIO.getImageWritersByFormatName(name).next();
        assertInstanceOf(SmaltImageWriter.class, writer);
        assertTrue(writer.canWriteSequence());
        var bytes = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            for (var frame : frames()) {
                writer.writeToSequence(new IIOImage(frame, null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static int frameCount(byte[] data) {
        try (var reader = Images.openAnimation(MediaSource.of(data))) {
            var count = 0;
            while (reader.read() != null) {
                count++;
            }
            return count;
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"apng, PNG", "smalt-gif, GIF", "SMALT-GIF, GIF", "APNG, PNG"})
    @DisplayName("sequences are written as animations")
    void sequences(String name, ImageFormat format) throws IOException {
        var data = sequence(name);
        assertEquals(format, Images.detect(MediaSource.of(data)).orElseThrow());
        assertEquals(4, frameCount(data));
    }

    @Test
    @DisplayName("the JDK keeps the png and gif names, suffixes and MIME types")
    void jdkNames() {
        for (var iterator = ImageIO.getImageWritersByFormatName("gif"); iterator.hasNext(); ) {
            assertFalse(iterator.next() instanceof SmaltImageWriter, "gif");
        }
        for (var iterator = ImageIO.getImageWritersBySuffix("gif"); iterator.hasNext(); ) {
            assertFalse(iterator.next() instanceof SmaltImageWriter, "gif suffix");
        }
        for (var iterator = ImageIO.getImageWritersByFormatName("png"); iterator.hasNext(); ) {
            assertFalse(iterator.next() instanceof SmaltImageWriter, "png");
        }
        assertTrue(ImageIO.getImageWritersBySuffix("apng").next() instanceof SmaltImageWriter);
        assertTrue(ImageIO.getImageWritersByMIMEType("image/apng").next() instanceof SmaltImageWriter);
    }

    @Test
    @DisplayName("ImageIO.write writes still images under the Smalt names")
    void stills() throws IOException {
        var image = frames().getFirst();
        for (var name : List.of("smalt-gif", "apng")) {
            var bytes = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(image, name, bytes), name);
            assertEquals(1, frameCount(bytes.toByteArray()), name);
        }
    }
}
