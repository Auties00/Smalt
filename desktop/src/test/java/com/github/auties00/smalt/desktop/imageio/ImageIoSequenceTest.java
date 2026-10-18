package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers animated WebP writing through the ImageIO sequence API: the bytes match the registry's
 * animation writer, and frame delays and loop counts carry over from Smalt and JDK GIF metadata.
 */
@DisplayName("ImageIO WebP sequences")
class ImageIoSequenceTest {
    private static final String GIF_FORMAT = "javax_imageio_gif_image_1.0";

    private static List<BufferedImage> frames(boolean alpha) {
        var frames = new ArrayList<BufferedImage>();
        for (var i = 0; i < 3; i++) {
            var frame = BufferedImages.toBufferedImage(BufferedImagesTest.gradient(48, 32, alpha));
            var graphics = frame.createGraphics();
            graphics.setColor(new Color(40 + i * 80, 200 - i * 60, i * 100));
            graphics.fillRect(4 + i * 12, 6, 10, 12);
            graphics.dispose();
            frames.add(frame);
        }
        return frames;
    }

    private static ImageWriter webpWriter() {
        return ImageIO.getImageWritersByFormatName("webp").next();
    }

    private static ImageWriteParam lossless(ImageWriter writer) {
        var param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType(SmaltImageWriteParam.LOSSLESS);
        return param;
    }

    private static byte[] writeSequence(List<IIOImage> images) throws IOException {
        var writer = webpWriter();
        var bytes = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            for (var image : images) {
                writer.writeToSequence(image, lossless(writer));
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static List<Integer> durations(byte[] webp) {
        var result = new ArrayList<Integer>();
        try (var reader = Images.openAnimation(MediaSource.of(webp))) {
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                result.add(frame.durationMillis());
            }
        }
        return result;
    }

    private static int loopCount(byte[] webp) {
        try (var reader = Images.openAnimation(MediaSource.of(webp))) {
            return reader.loopCount();
        }
    }

    @Test
    @DisplayName("writes the bytes of the registry's animation writer, 100 ms per frame without metadata")
    void sameBytes() throws IOException {
        var frames = frames(true);
        var images = frames.stream().map(frame -> new IIOImage(frame, null, null)).toList();
        assertTrue(webpWriter().canWriteSequence());
        var data = writeSequence(images);
        var sink = MediaSink.memory();
        try (var writer = Images.openAnimationWriter(ImageFormat.WEBP, 48, 32, ImageEncoderOptions.lossless(), sink)) {
            for (var frame : frames) {
                writer.write(BufferedImages.toPicture(frame), 100_000);
            }
            writer.finish();
        }
        assertArrayEquals(sink.toByteArray(), data);
        assertEquals(List.of(100, 100, 100), durations(data));
    }

    @Test
    @DisplayName("keeps the delays and loop count of a WebP animation read through ImageIO")
    void smaltMetadata() throws IOException {
        var sink = MediaSink.memory();
        try (var writer = Images.openAnimationWriter(ImageFormat.WEBP, 48, 32,
                ImageEncoderOptions.lossless().toBuilder().loopCount(3).build(), sink)) {
            var delays = new int[]{40, 80, 120};
            var frames = frames(false);
            for (var i = 0; i < frames.size(); i++) {
                writer.write(BufferedImages.toPicture(frames.get(i)), delays[i] * 1000L);
            }
            writer.finish();
        }
        var images = new ArrayList<IIOImage>();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(sink.toByteArray()))) {
            var reader = ImageIO.getImageReaders(input).next();
            reader.setInput(input);
            for (var i = 0; i < reader.getNumImages(true); i++) {
                images.add(reader.readAll(i, null));
            }
            reader.dispose();
        }
        var copy = writeSequence(images);
        assertEquals(List.of(40, 80, 120), durations(copy));
        assertEquals(3, loopCount(copy));
    }

    @Test
    @DisplayName("takes the delays and loop count of the JDK GIF reader's metadata")
    void gifMetadata() throws IOException {
        var gifWriter = ImageIO.getImageWritersByFormatName("gif").next();
        var gif = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(gif)) {
            gifWriter.setOutput(output);
            gifWriter.prepareWriteSequence(null);
            for (var frame : frames(false)) {
                var metadata = gifWriter.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame), null);
                var root = (IIOMetadataNode) metadata.getAsTree(GIF_FORMAT);
                var control = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("delayTime", "7");
                var extensions = new IIOMetadataNode("ApplicationExtensions");
                var netscape = new IIOMetadataNode("ApplicationExtension");
                netscape.setAttribute("applicationID", "NETSCAPE");
                netscape.setAttribute("authenticationCode", "2.0");
                netscape.setUserObject(new byte[]{1, 2, 0});
                extensions.appendChild(netscape);
                root.appendChild(extensions);
                metadata.mergeTree(GIF_FORMAT, root);
                gifWriter.writeToSequence(new IIOImage(frame, null, metadata), null);
            }
            gifWriter.endWriteSequence();
        } finally {
            gifWriter.dispose();
        }
        var images = new ArrayList<IIOImage>();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(gif.toByteArray()))) {
            var reader = ImageIO.getImageReaders(input).next();
            reader.setInput(input);
            for (var i = 0; i < reader.getNumImages(true); i++) {
                images.add(reader.readAll(i, null));
            }
            reader.dispose();
        }
        var webp = writeSequence(images);
        assertEquals(List.of(70, 70, 70), durations(webp));
        assertEquals(2, loopCount(webp));
    }

    @Test
    @DisplayName("rejects misuse of the sequence calls")
    void misuse() throws IOException {
        var writer = webpWriter();
        var frame = frames(false).getFirst();
        assertThrows(IllegalStateException.class, () -> writer.prepareWriteSequence(null));
        try (var output = ImageIO.createImageOutputStream(new ByteArrayOutputStream())) {
            writer.setOutput(output);
            assertThrows(IllegalStateException.class, () -> writer.writeToSequence(new IIOImage(frame, null, null), null));
            assertThrows(IllegalStateException.class, writer::endWriteSequence);
            writer.prepareWriteSequence(null);
            assertThrows(IllegalStateException.class, () -> writer.prepareWriteSequence(null));
            assertThrows(IIOException.class, writer::endWriteSequence);
            writer.prepareWriteSequence(null);
            writer.writeToSequence(new IIOImage(frame, null, null), null);
            var other = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
            assertThrows(IllegalArgumentException.class, () -> writer.writeToSequence(new IIOImage(other, null, null), null));
        } finally {
            writer.dispose();
        }
    }
}
