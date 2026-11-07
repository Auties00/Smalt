package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the color profile of the animated WebP encoder: the ICCP chunk after VP8X with the ICC
 * flag set, in lossless and lossy animations and in an animation collapsed into a still image,
 * read back by this module's decoder and by libwebp's webpmux, with the frames unchanged.
 */
class WebpAnimationColorProfileTest {
    private static List<RgbaPicture> frames(int count) {
        var frames = new ArrayList<RgbaPicture>();
        for (var i = 0; i < count; i++) {
            var picture = RgbaPicture.allocate(64, 48, true);
            var pixels = picture.pixels();
            for (var y = 0; y < 48; y++) {
                for (var x = 0; x < 64; x++) {
                    var p = (y * 64 + x) * 4;
                    pixels[p] = (byte) (x * 4 + i * 9);
                    pixels[p + 1] = (byte) (y * 5);
                    pixels[p + 2] = (byte) ((x ^ y) + i);
                    pixels[p + 3] = (byte) (x < 4 ? 0 : 255);
                }
            }
            frames.add(picture);
        }
        return frames;
    }

    private static byte[] encode(List<RgbaPicture> frames, WebpAnimationEncoderOptions options) {
        var encoder = new WebpAnimationEncoder(64, 48, options);
        for (var frame : frames) {
            encoder.addFrame(frame, 100);
        }
        return encoder.finish();
    }

    private static List<byte[]> decodeFrames(byte[] file) {
        var list = new ArrayList<byte[]>();
        try (var reader = WebpDecoder.openAnimation(MediaSource.of(file))) {
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                list.add(RasterTesting.tight((RgbaPicture) frame.picture()));
            }
        }
        return list;
    }

    private static void assertProfile(byte[] file, ColorProfile profile) {
        assertEquals('V', file[12]);
        assertEquals('X', file[15]);
        assertEquals(0x20, file[20] & 0x20, "VP8X ICC flag");
        assertEquals("ICCP", new String(file, 30, 4, java.nio.charset.StandardCharsets.US_ASCII),
                "ICCP right after VP8X");
        assertArrayEquals(profile.toByteArray(), WebpContainer.iccProfile(file).orElseThrow());
        assertEquals(profile, WebpDecoder.decode(MediaSource.of(file)).colorProfile().orElseThrow());
    }

    @Test
    @DisplayName("embeds a Display P3 profile in lossless and lossy animations without changing the frames")
    void animations() throws IOException {
        var frames = frames(4);
        for (var base : List.of(WebpAnimationEncoderOptions.lossless(), WebpAnimationEncoderOptions.lossy(70))) {
            var plain = encode(frames, base);
            var tagged = encode(frames, base.toBuilder().colorProfile(ColorProfile.displayP3()).build());
            assertProfile(tagged, ColorProfile.displayP3());
            assertTrue(WebpContainer.iccProfile(plain).isEmpty());
            var expected = decodeFrames(plain);
            var actual = decodeFrames(tagged);
            assertEquals(expected.size(), actual.size());
            for (var i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i), actual.get(i), "frame " + i);
            }
            if (Oracle.find("webpmux").isPresent()) {
                var path = RasterTesting.write("animated-p3-" + base.isLossless() + ".webp", tagged);
                var icc = path.resolveSibling(path.getFileName() + ".icc");
                Oracle.run("webpmux", Duration.ofMinutes(1), null, List.of("-get", "icc", path.toString(), "-o",
                        icc.toString()));
                assertArrayEquals(ColorProfile.displayP3().toByteArray(), Files.readAllBytes(icc));
                var info = new String(Oracle.run("webpmux", Duration.ofMinutes(1), null, List.of("-info", path.toString())),
                        java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(info.contains("ICC profile"), info);
                assertTrue(info.contains("animation"), info);
            }
        }
    }

    @Test
    @DisplayName("keeps the profile when the animation collapses into a still image")
    void still() {
        var frames = frames(1);
        var file = encode(frames, WebpAnimationEncoderOptions.builder().colorProfile(ColorProfile.adobeRgb()).build());
        assertProfile(file, ColorProfile.adobeRgb());
        RasterTesting.assertSameVisible(RasterTesting.tight(frames.getFirst()),
                RasterTesting.tight((RgbaPicture) WebpDecoder.decode(MediaSource.of(file)).picture()), 64, "still");
    }

    @Test
    @DisplayName("embeds a high-dynamic-range profile in its ICC form and compares, prints and resets the option")
    void options() {
        var hdr = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                TransferCharacteristics.PQ));
        var file = encode(frames(3), WebpAnimationEncoderOptions.builder().colorProfile(hdr).build());
        assertProfile(file, hdr);
        assertTrue(WebpDecoder.decode(MediaSource.of(file)).colorProfile().orElseThrow().isHighDynamicRange());
        assertThrows(NullPointerException.class, () -> WebpAnimationEncoderOptions.builder().colorProfile(null));
        var options = WebpAnimationEncoderOptions.builder().colorProfile(ColorProfile.displayP3()).build();
        assertEquals(options, options.toBuilder().build());
        assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
        assertNotEquals(WebpAnimationEncoderOptions.defaults(), options);
        assertTrue(options.toString().contains("Display P3"));
        assertEquals(WebpAnimationEncoderOptions.defaults(), options.toBuilder().noColorProfile().build());
    }
}
