package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@DisplayName("SmaltImageMetadata and SmaltImageIo")
class SmaltImageMetadataTest {
    static SmaltImageMetadata metadata(SmaltImageMetadata.Frame frame) {
        return new SmaltImageMetadata(ImageFormat.WEBP, 8, 4, false, false, Orientation.NORMAL, frame,
                Optional.empty(), Optional.empty());
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
