package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Reads camera raw files through ImageIO, which must reach Smalt's reader rather than the JDK's TIFF
 * reader for the TIFF-structured ones (the NEF's sensor directory lies beyond the bytes the reader
 * peeks).
 */
class RawImageIoTest {
    @ParameterizedTest(name = "{0}")
    @DisplayName("ImageIO.read renders TIFF-structured raw files at their full size")
    @CsvSource({
            "rawpixls-canon-eos-5d-mark-iii-16bit-16-9.dng, 1904, 1064",
            "rawpixls-nikon-d70s-12bit-12bit-compressed-lossy-type-1-3-2.nef, 3008, 2000"
    })
    void read(String name, int width, int height) throws IOException {
        var file = Conformance.vector(name).toFile();
        var image = ImageIO.read(file);
        assertNotNull(image);
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
    }
}
