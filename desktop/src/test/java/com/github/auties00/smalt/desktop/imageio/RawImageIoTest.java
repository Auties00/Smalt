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
            "rawpixls-canon-eos-5d-mark-iii-16bit-16-9.dng, https://raw.pixls.us/getfile.php/2649/nice/Canon%20-%20EOS%205D%20Mark%20III%20-%2016bit%20%2816%3A9%29.dng, bc29ac8a37c800346749b9a5a1adc2722357f7f0e49df05a7104454881604b92, 1904, 1064",
            "rawpixls-nikon-d70s-12bit-12bit-compressed-lossy-type-1-3-2.nef, https://raw.pixls.us/getfile.php/1117/nice/Nikon%20-%20D70s%20-%2012bit%2012bit%20compressed%20%28Lossy%20%28type%201%29%29%20%283%3A2%29.NEF, 6c46f9854033e7623ebbb5de8c951df6762ca44f05d45035ccc5cb7e9f4e5692, 3008, 2000"
    })
    void read(String name, String url, String sha256, int width, int height) throws IOException {
        var file = Conformance.fetch(name, url, sha256).toFile();
        var image = ImageIO.read(file);
        assertNotNull(image);
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
    }
}
