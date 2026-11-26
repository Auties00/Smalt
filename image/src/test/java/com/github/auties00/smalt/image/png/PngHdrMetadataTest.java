package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the PNG Third Edition metadata chunks of the Web Platform Tests files against the values the chunks store,
 * as the specification defines them: the {@code cLLI} content light levels (two 32-bit values in units of 0.0001
 * cd/m2) and the {@code mDCV} mastering display (three primaries and a white point as 16-bit chromaticities in units
 * of 0.00002, then the maximum and minimum luminance as 32-bit values in units of 0.0001 cd/m2) of the PQ images of
 * {@code html/canvas/element/global-hdr-headroom}, whose color space is a PQ ICC profile; and the {@code eXIf}
 * orientation of {@code png/exif-chunk.html}.
 */
@DisplayName("PNG HDR metadata and eXIf of the Web Platform Tests")
class PngHdrMetadataTest {
    private static byte[] read(String name) {
        try {
            return Files.readAllBytes(Conformance.vector(name));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** {@return the data of the first chunk of a type, if any} */
    static Optional<byte[]> chunk(byte[] png, String type) {
        var at = 8;
        while (at + 12 <= png.length) {
            var length = ByteBuffer.wrap(png, at, 4).getInt();
            var name = new String(png, at + 4, 4, StandardCharsets.US_ASCII);
            if (name.equals(type)) {
                return Optional.of(Arrays.copyOfRange(png, at + 8, at + 8 + length));
            }
            at += 12 + length;
        }
        return Optional.empty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"pq-clli_none-mdcv_none,false,false", "pq-clli_100-mdcv_p3_5000,true,true",
            "pq-clli_500-mdcv_none,true,false", "pq-clli_none-mdcv_rec2020_5000,false,true"})
    @DisplayName("reports the PQ color space with the content light level and mastering display the chunks store")
    void hdrMetadata(String name, boolean lightLevel, boolean mastering) {
        var file = read("png-wpt-" + name + ".png");
        var image = PngDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults());
        var profile = image.colorProfile().orElseThrow();
        assertTrue(profile.isHighDynamicRange(), "a PQ profile");
        var info = profile.colorInfo().orElseThrow();
        assertEquals(TransferCharacteristics.PQ, info.transfer());
        var clli = chunk(file, "cLLI");
        assertEquals(lightLevel, clli.isPresent(), "the file has a cLLI chunk");
        if (lightLevel) {
            var data = ByteBuffer.wrap(clli.get());
            var expected = new ContentLightLevel((int) Math.round(data.getInt() / 10_000.0),
                    (int) Math.round(data.getInt() / 10_000.0));
            assertEquals(Optional.of(expected), info.contentLightLevel());
        } else {
            assertEquals(Optional.empty(), info.contentLightLevel());
        }
        var mdcv = chunk(file, "mDCV");
        assertEquals(mastering, mdcv.isPresent(), "the file has an mDCV chunk");
        if (mastering) {
            var data = ByteBuffer.wrap(mdcv.get());
            var red = new Chromaticity((data.getShort() & 0xFFFF) * 0.00002, (data.getShort() & 0xFFFF) * 0.00002);
            var green = new Chromaticity((data.getShort() & 0xFFFF) * 0.00002, (data.getShort() & 0xFFFF) * 0.00002);
            var blue = new Chromaticity((data.getShort() & 0xFFFF) * 0.00002, (data.getShort() & 0xFFFF) * 0.00002);
            var white = new Chromaticity((data.getShort() & 0xFFFF) * 0.00002, (data.getShort() & 0xFFFF) * 0.00002);
            var maximum = (data.getInt() & 0xFFFFFFFFL) * 0.0001;
            var minimum = (data.getInt() & 0xFFFFFFFFL) * 0.0001;
            var actual = info.masteringDisplay().orElseThrow();
            assertChromaticity(red, actual.red(), "red");
            assertChromaticity(green, actual.green(), "green");
            assertChromaticity(blue, actual.blue(), "blue");
            assertChromaticity(white, actual.whitePoint(), "white point");
            assertEquals(maximum, actual.maxLuminance(), 1e-6, "maximum luminance");
            assertEquals(minimum, actual.minLuminance(), 1e-9, "minimum luminance");
            assertEquals(5000, maximum, 1e-9, "the file name's 5000 cd/m2");
        } else {
            assertEquals(Optional.empty(), info.masteringDisplay());
        }
    }

    @Test
    @DisplayName("describes a Display P3 cICP chunk by its code points, over an iCCP profile too")
    void cicp() {
        // png/cicp-chunk.html and png/cICP-wins.html: cICP 12, 13, 0, 1 (SMPTE EG 432 primaries, the sRGB transfer,
        // RGB, full range) declares Display P3, and takes precedence over the file's iCCP profile
        var expected = com.github.auties00.smalt.image.color.ColorProfile.of(new ColorInfo(
                com.github.auties00.smalt.image.MatrixCoefficients.IDENTITY, com.github.auties00.smalt.image.ColorRange.FULL,
                com.github.auties00.smalt.image.ColorPrimaries.of(12), TransferCharacteristics.of(13)));
        for (var name : java.util.List.of("png-wpt-cicp-display-p3.png", "png-wpt-cICP-and-iCCP.png")) {
            var file = read(name);
            assertArrayEquals(new byte[]{12, 13, 0, 1}, chunk(file, "cICP").orElseThrow(), name);
            var image = PngDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults());
            assertEquals(expected, image.colorProfile().orElseThrow(), name);
            assertEquals(chunk(file, "iCCP").isPresent(), image.iccProfile().isPresent(), name + ": iCCP bytes");
        }
        assertEquals(12, com.github.auties00.smalt.image.ColorPrimaries.of(12).code());
    }

    @Test
    @DisplayName("applies the cICP chunk to the frames of an animation stored only in fdAT chunks")
    void fdatInheritsCicp() {
        // png/apng/fDAT-inherits-cICP.html: an APNG whose default image is not part of the animation and whose
        // frame is in an fdAT chunk, after a cICP chunk declaring Display P3, which applies to that frame too
        var file = read("png-wpt-apng-062-fdAT-inherits-cICP.png");
        assertTrue(chunk(file, "fdAT").isPresent());
        assertArrayEquals(new byte[]{12, 13, 0, 1}, chunk(file, "cICP").orElseThrow());
        var expected = com.github.auties00.smalt.image.color.ColorProfile.of(new ColorInfo(
                com.github.auties00.smalt.image.MatrixCoefficients.IDENTITY, com.github.auties00.smalt.image.ColorRange.FULL,
                com.github.auties00.smalt.image.ColorPrimaries.of(12), TransferCharacteristics.of(13)));
        var image = PngDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults());
        assertEquals(expected, image.colorProfile().orElseThrow());
        try (var animation = PngDecoder.openAnimation(MediaSource.of(file), ImageDecoderOptions.defaults())) {
            var frame = animation.read();
            assertNotNull(frame, "the fdAT frame");
            assertEquals(image.picture().width(), frame.picture().width());
            assertNull(animation.read(), "one frame");
        }
    }

    private static void assertChromaticity(Chromaticity expected, Chromaticity actual, String what) {
        assertEquals(expected.x(), actual.x(), 1e-9, what + " x");
        assertEquals(expected.y(), actual.y(), 1e-9, what + " y");
    }

    @Test
    @DisplayName("reads the eXIf chunk's orientation and its Exif block")
    void exifOrientation() {
        // png/exif-chunk.html: the eXIf chunk holds a big-endian TIFF structure whose IFD0 Orientation is 6 (the
        // 0th row on the right, the 0th column at the top: turn 90 degrees clockwise to display)
        var file = read("png-wpt-exif-orientation-bottom-right.png");
        var exif = chunk(file, "eXIf").orElseThrow();
        assertEquals(6, orientationTag(exif));
        var image = PngDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults());
        assertEquals(Orientation.ROTATE_90, image.orientation());
        assertArrayEquals(exif, image.exif().orElseThrow());
        var pixels = RasterTesting.tight(image.picture());
        assertEquals(image.picture().width() * image.picture().height() * 4, pixels.length);
    }

    /** {@return the value of the Orientation tag (0x0112) of IFD0 of a TIFF structure} */
    static int orientationTag(byte[] tiff) {
        var little = tiff[0] == 'I';
        var buffer = ByteBuffer.wrap(tiff).order(little ? java.nio.ByteOrder.LITTLE_ENDIAN
                : java.nio.ByteOrder.BIG_ENDIAN);
        var ifd = buffer.getInt(4);
        var count = buffer.getShort(ifd) & 0xFFFF;
        for (var entry = 0; entry < count; entry++) {
            var at = ifd + 2 + 12 * entry;
            if ((buffer.getShort(at) & 0xFFFF) == 0x0112) {
                return buffer.getShort(at + 8) & 0xFFFF;
            }
        }
        return -1;
    }
}
