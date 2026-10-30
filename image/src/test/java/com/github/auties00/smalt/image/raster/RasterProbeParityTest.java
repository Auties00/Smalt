package com.github.auties00.smalt.image.raster;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.image.Images;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Checks that the core probe ({@link Media#probe(MediaSource)}) describes the behavior samples of the raster formats
 * as their decoders do: the same format, size and transparency as the decoder's probe and as the decoded picture
 * (for ICNS, whose core probe reports the transparency of the whole family, only the size).
 */
@DisplayName("raster probe parity")
class RasterProbeParityTest {
    @ParameterizedTest
    @ValueSource(strings = {"dds/bc6h.dds", "dds/bc7.dds", "dds/cube-bgra.dds", "dds/dxt1-mipmaps.dds", "dds/dxt5.dds",
            "exr/rle-float.exr", "exr/zip-half.exr", "hdr/rle.hdr", "icns/legacy.icns", "icns/modern.icns",
            "icns/stored.icns", "jxr/bgra8-q40-a2.jxr", "jxr/cmyk8-ic-q80.jxr", "jxr/gray16-q40.jxr",
            "jxr/rgb8-q40.jxr", "jxr/rgbaf-q40-a3.jxr", "pnm/float.pfm", "pnm/gray16.pgm", "pnm/mono.pbm",
            "pnm/rgb.ppm", "pnm/rgba.pam", "psd/cmyk-zip.psd", "psd/gray16-raw.psd", "psd/rgb-rle.psb",
            "psd/rgba-rle.psd", "qoi/rgba.qoi", "tga/raw-bgr.tga", "tga/rle-bgra.tga", "tga/rle-pal8.tga"})
    @DisplayName("Media.probe agrees with the decoder's probe and the decoded picture")
    void samples(String name) {
        var file = sample(name);
        var probed = Media.probe(MediaSource.of(file)).image().orElseThrow();
        var info = Images.probe(MediaSource.of(file));
        var decoded = Images.decode(MediaSource.of(file));
        assertEquals(info.format(), probed.format(), name);
        assertEquals(info.width(), probed.width(), name);
        assertEquals(info.height(), probed.height(), name);
        if (!name.startsWith("icns/")) {
            // the core probe reports an icon family's alpha over all its images, the decoder that of the one it
            // decodes
            assertEquals(info.hasAlpha(), probed.hasAlpha(), name);
        }
        assertEquals(decoded.picture().hasAlpha(), info.hasAlpha(), name);
    }

    private static byte[] sample(String name) {
        try (var input = RasterProbeParityTest.class.getResourceAsStream("/samples/" + name)) {
            assertNotNull(input, name);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
