package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.tiff.TiffEncoder;
import com.github.auties00.smalt.image.tiff.TiffEncoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@DisplayName("SmaltImageMetadata and SmaltImageIo")
class SmaltImageMetadataTest {
    static SmaltImageMetadata metadata(SmaltImageMetadata.Frame frame) {
        return new SmaltImageMetadata(ImageFormat.WEBP, 8, 4, false, false, Orientation.NORMAL, frame,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static String standard(SmaltImageMetadata metadata, String name) {
        var tree = (IIOMetadataNode) metadata.getAsTree(IIOMetadataFormatImpl.standardMetadataFormatName);
        var nodes = tree.getElementsByTagName(name);
        return nodes.getLength() == 0 ? null : ((IIOMetadataNode) nodes.item(0)).getAttribute("value");
    }

    @Test
    @DisplayName("the standard tree gives the pixel aspect ratio and, for a physical density, the pixel size in millimeters")
    void density() {
        var physical = new SmaltImageMetadata(ImageFormat.TIFF, 8, 4, false, false, Orientation.NORMAL, null,
                Optional.empty(), Optional.empty(), Optional.of(PixelDensity.ofDotsPerCentimeter(40, 20)));
        assertEquals(Optional.of(PixelDensity.ofDotsPerCentimeter(40, 20)), physical.density());
        assertEquals("0.5", standard(physical, "PixelAspectRatio"));
        assertEquals("0.25", standard(physical, "HorizontalPixelSize"));
        assertEquals("0.5", standard(physical, "VerticalPixelSize"));
        var root = (IIOMetadataNode) physical.getAsTree(SmaltImageMetadataFormat.NAME);
        var node = (IIOMetadataNode) root.getElementsByTagName("Density").item(0);
        assertEquals("dots_per_centimeter", node.getAttribute("unit"));
        assertEquals("40.0", node.getAttribute("horizontal"));
        assertEquals("20.0", node.getAttribute("vertical"));
        var format = physical.getMetadataFormat(SmaltImageMetadataFormat.NAME);
        assertEquals(List.of("aspect_ratio", "dots_per_inch", "dots_per_centimeter", "dots_per_meter"),
                List.of(format.getAttributeEnumerations("Density", "unit")));
        assertEquals(0, ((IIOMetadataNode) metadata(null).getAsTree(SmaltImageMetadataFormat.NAME))
                .getElementsByTagName("Density").getLength());
        var aspect = new SmaltImageMetadata(ImageFormat.TIFF, 8, 4, false, false, Orientation.NORMAL, null,
                Optional.empty(), Optional.empty(), Optional.of(PixelDensity.ofAspectRatio(1, 3)));
        assertEquals("3.0", standard(aspect, "PixelAspectRatio"));
        assertEquals(null, standard(aspect, "HorizontalPixelSize"));
        assertEquals(null, standard(metadata(null), "PixelAspectRatio"));
        assertEquals(null, standard(metadata(null), "VerticalPixelSize"));
        assertEquals(Optional.empty(), metadata(null).density());
    }

    @Test
    @DisplayName("the reader reports the density a TIFF page records")
    void readerDensity() throws IOException {
        var tiff = TiffEncoder.encode(RgbaPicture.allocate(8, 4, false), TiffEncoderOptions.builder()
                .density(PixelDensity.ofDotsPerInch(254, 127)).build());
        var reader = ImageIO.getImageReadersByFormatName(SmaltImageIo.TIFF).next();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(tiff))) {
            reader.setInput(input);
            var metadata = (SmaltImageMetadata) reader.getImageMetadata(0);
            assertEquals(Optional.of(PixelDensity.ofDotsPerInch(254, 127)), metadata.density());
            assertEquals("0.1", standard(metadata, "HorizontalPixelSize"));
            assertEquals("0.2", standard(metadata, "VerticalPixelSize"));
        } finally {
            reader.dispose();
        }
    }

    @Test
    @DisplayName("a still image has no frame delay and no loop count")
    void still() {
        var metadata = metadata(null);
        assertEquals(OptionalLong.empty(), metadata.delayMillis());
        assertEquals(OptionalInt.empty(), metadata.loopCount());
    }

    @ParameterizedTest(name = "delay {0} ms, {1} loops")
    @CsvSource({"100, 0", "40, 3", "0, 65535"})
    @DisplayName("an animation frame reports the delay and loop count of its native tree")
    void animated(long delay, int loops) {
        var metadata = metadata(new SmaltImageMetadata.Frame(2, 200, delay, loops));
        assertEquals(OptionalLong.of(delay), metadata.delayMillis());
        assertEquals(OptionalInt.of(loops), metadata.loopCount());
        var root = (IIOMetadataNode) metadata.getAsTree(SmaltImageMetadataFormat.NAME);
        var animation = (IIOMetadataNode) root.getElementsByTagName("Animation").item(0);
        assertEquals(Long.toString(delay), animation.getAttribute("delayMillis"));
        assertEquals(Integer.toString(loops), animation.getAttribute("loopCount"));
    }

    @Test
    @DisplayName("the SmaltImageIo names select Smalt's TIFF and GIF plug-ins")
    void formatNames() {
        assertEquals("smalt-tiff", SmaltImageIo.TIFF);
        assertEquals("smalt-gif", SmaltImageIo.GIF);
        assertInstanceOf(SmaltImageReader.class, ImageIO.getImageReadersByFormatName(SmaltImageIo.TIFF).next());
        assertInstanceOf(SmaltImageWriter.class, ImageIO.getImageWritersByFormatName(SmaltImageIo.TIFF).next());
        assertInstanceOf(SmaltImageWriter.class, ImageIO.getImageWritersByFormatName(SmaltImageIo.GIF).next());
    }
}
