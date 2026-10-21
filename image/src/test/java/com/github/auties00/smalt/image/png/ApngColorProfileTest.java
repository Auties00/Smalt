package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.github.auties00.smalt.image.png.PngEncoderFormatsTest.narrow;
import static com.github.auties00.smalt.image.png.PngEncoderFormatsTest.photo;
import static com.github.auties00.smalt.image.png.PngEncoderFormatsTest.types;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the color profile of the APNG encoder: an ICC profile as iCCP and a high-dynamic-range
 * profile as cICP, mDCV and cLLI, placed before acTL, read back by this module's decoder and by
 * libpng, with the frames unchanged.
 */
class ApngColorProfileTest {
    private static List<RgbaPicture> frames(int count, boolean palette) {
        var frames = new ArrayList<RgbaPicture>();
        for (var i = 0; i < count; i++) {
            var shift = i;
            frames.add(narrow(48, 32, false, palette
                    ? (x, y) -> 0xFF00_0000 | ((x + shift) / 6 % 4) * 0x40_2010
                    : (x, y) -> photo(x + shift * 3, y)));
        }
        return frames;
    }

    private static byte[] encode(List<RgbaPicture> frames, ApngEncoderOptions options) {
        var encoder = new ApngEncoder(48, 32, options);
        for (var frame : frames) {
            encoder.addFrame(frame, 1, 10);
        }
        return encoder.finish();
    }

    private static void assertFrames(List<RgbaPicture> frames, byte[] apng) {
        try (var reader = PngDecoder.openAnimation(MediaSource.of(apng))) {
            for (var frame : frames) {
                var read = reader.read();
                assertNotNull(read);
                assertArrayEquals(RasterTesting.tight(frame), RasterTesting.tight((RgbaPicture) read.picture()));
            }
            assertNull(reader.read());
        }
    }

    @Test
    @DisplayName("writes a Display P3 iCCP before acTL in truecolor and indexed animations")
    void iccProfile() {
        for (var palette : new boolean[]{false, true}) {
            var frames = frames(4, palette);
            var options = ApngEncoderOptions.builder().colorProfile(ColorProfile.displayP3()).build();
            var apng = encode(frames, options);
            var order = types(apng);
            assertEquals("IHDR", order.get(0));
            assertEquals("iCCP", order.get(1));
            assertTrue(order.indexOf("iCCP") < order.indexOf("acTL"), order.toString());
            assertEquals(palette, order.contains("PLTE"));
            if (palette) {
                assertTrue(order.indexOf("iCCP") < order.indexOf("PLTE"), order.toString());
            }
            var decoded = PngDecoder.decode(MediaSource.of(apng));
            assertEquals(ColorProfile.displayP3(), decoded.colorProfile().orElseThrow());
            assertArrayEquals(ColorProfile.displayP3().toByteArray(), decoded.iccProfile().orElseThrow());
            assertFrames(frames, apng);
            var plain = encode(frames, ApngEncoderOptions.defaults());
            assertFalse(types(plain).contains("iCCP"));
            if (PngOracles.hasLibpng()) {
                var info = PngOracles.libpngInfo(apng);
                assertEquals("iCCP ICC profile " + ColorProfile.displayP3().toByteArray().length,
                        PngOracles.libpngChunk(info, "iCCP"));
                assertTrue(info.stream().noneMatch(line -> line.startsWith("warning")), info.toString());
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                Oracle.assertDecodesCleanly(PngOracles.file("apng-p3", apng));
            }
        }
    }

    @Test
    @DisplayName("keeps the profile when a single frame is written as a plain PNG")
    void singleFrame() {
        var frames = frames(1, false);
        var png = encode(frames, ApngEncoderOptions.builder().colorProfile(ColorProfile.adobeRgb()).build());
        assertFalse(types(png).contains("acTL"));
        assertEquals(ColorProfile.adobeRgb(), PngDecoder.decode(MediaSource.of(png)).colorProfile().orElseThrow());
    }

    @Test
    @DisplayName("writes a PQ profile as cICP, mDCV, cLLI and iCCP before acTL")
    void highDynamicRange() {
        var color = new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                TransferCharacteristics.PQ)
                .withMasteringDisplay(new MasteringDisplay(new Chromaticity(0.68, 0.32), new Chromaticity(0.265, 0.69),
                        new Chromaticity(0.15, 0.06), new Chromaticity(0.3127, 0.329), 1000, 0.005))
                .withContentLightLevel(new ContentLightLevel(800, 250));
        var profile = ColorProfile.of(color);
        var frames = frames(3, false);
        var encoder = new ApngEncoder(48, 32, ApngEncoderOptions.builder().colorProfile(profile).build());
        encoder.setFallbackImage(frames.getFirst());
        for (var frame : frames) {
            encoder.addFrame(frame, 1, 10);
        }
        var apng = encoder.finish();
        var order = types(apng);
        assertEquals(List.of("IHDR", "cICP", "mDCV", "cLLI", "iCCP", "acTL"), order.subList(0, 6));
        assertArrayEquals(profile.toByteArray(), PngDecoder.decode(MediaSource.of(apng)).iccProfile().orElseThrow());
        var read = PngDecoder.decode(MediaSource.of(apng)).colorProfile().orElseThrow();
        assertEquals(profile, read);
        assertEquals(color.masteringDisplay(), read.colorInfo().orElseThrow().masteringDisplay());
        assertFrames(frames, apng);
        if (PngOracles.hasLibpng()) {
            var info = PngOracles.libpngInfo(apng);
            assertEquals("cICP 9 16 0 1", PngOracles.libpngChunk(info, "cICP"));
            assertEquals("cLLI 8000000 2500000", PngOracles.libpngChunk(info, "cLLI"));
        }
    }

    @Test
    @DisplayName("validates, compares and resets the profile option")
    void options() {
        var options = ApngEncoderOptions.builder().colorProfile(ColorProfile.displayP3()).build();
        assertEquals(options, options.toBuilder().build());
        assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
        assertNotEquals(ApngEncoderOptions.defaults(), options);
        assertTrue(options.toString().contains("Display P3"));
        assertEquals(ApngEncoderOptions.defaults(), options.toBuilder().noColorProfile().build());
        assertThrows(NullPointerException.class, () -> ApngEncoderOptions.builder().colorProfile(null));
        assertThrows(MediaException.Unsupported.class,
                () -> ApngEncoderOptions.builder().colorProfile(ColorProfile.grayGamma22()).build());
    }
}
