package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers separated pages of named inks ({@code InkSet} 2) converted through their embedded profile of five or six
 * colorants, against Little CMS 2.17's conversion of the same samples (the vectors of
 * {@code tools/testdata/tiff-inks}, pinned by {@code inks.sha256}).
 */
@DisplayName("TIFF multi-ink separations")
class TiffInksTest {
    private static final String VECTORS = "/com/github/auties00/smalt/image/tiff/inks/";

    private static byte[] vector(String name) {
        return TiffJpeg12Test.resource(VECTORS + name);
    }

    @Test
    @DisplayName("the vectors match their pinned checksums")
    void checksums() throws Exception {
        var manifest = new String(TiffJpeg12Test.resource("/com/github/auties00/smalt/image/tiff/inks.sha256"),
                StandardCharsets.US_ASCII);
        var lines = manifest.lines().filter(line -> !line.isBlank()).toList();
        assertEquals(6, lines.size());
        for (var line : lines) {
            var parts = line.trim().split("\\s+\\*?");
            var digest = MessageDigest.getInstance("SHA-256").digest(vector(parts[1]));
            assertEquals(parts[0], HexFormat.of().formatHex(digest), parts[1]);
        }
    }

    @Test
    @DisplayName("five 8-bit inks convert through a version 4 profile as Little CMS converts them")
    void fiveInks() {
        var image = TiffDecoder.decode(MediaSource.of(vector("inks5-8.tif")));
        var picture = assertInstanceOf(RgbaPicture.class, image.picture());
        assertFalse(picture.hasAlpha());
        assertEquals(41, picture.width());
        assertEquals(29, picture.height());
        assertEquals(ColorProfile.srgb(), image.colorProfile().orElseThrow());
        assertTrue(image.iccProfile().isEmpty());
        var expected = vector("inks5-8.ref.rgb");
        var worst = 0;
        for (var i = 0; i < 41 * 29; i++) {
            for (var c = 0; c < 3; c++) {
                worst = Math.max(worst, Math.abs((picture.pixels()[i * 4 + c] & 0xFF) - (expected[i * 3 + c] & 0xFF)));
            }
            assertEquals((byte) 0xFF, picture.pixels()[i * 4 + 3]);
        }
        assertTrue(worst <= 1, "max difference " + worst);
    }

    @Test
    @DisplayName("six 16-bit planar inks with alpha convert through a version 2 profile as Little CMS converts them")
    void sixInks() {
        var image = TiffDecoder.decode(MediaSource.of(vector("inks6-16a.tif")),
                ImageDecoderOptions.builder().highBitDepth(true).build());
        var picture = assertInstanceOf(Rgba16Picture.class, image.picture());
        assertTrue(picture.hasAlpha());
        var expected = vector("inks6-16a.ref.rgba");
        var worst = 0;
        for (var i = 0; i < 33 * 21; i++) {
            var alpha = u16(expected, i * 4 + 3);
            assertEquals(alpha, picture.pixels()[i * 4 + 3] & 0xFFFF, "alpha of pixel " + i);
            if (alpha == 0) {
                continue;
            }
            for (var c = 0; c < 3; c++) {
                worst = Math.max(worst, Math.abs((picture.pixels()[i * 4 + c] & 0xFFFF) - u16(expected, i * 4 + c)));
            }
        }
        // one 8-bit step: Little CMS interpolates its 16-bit pipeline differently from Smalt's
        assertTrue(worst <= 257, "max difference " + worst);
        var narrow = (RgbaPicture) TiffDecoder.decode(MediaSource.of(vector("inks6-16a.tif"))).picture();
        assertArrayEquals(ImageOps.toEightBit(picture).pixels(), narrow.pixels());
    }

    @Test
    @DisplayName("the conversion equals the channel picture converted through its profile")
    void channels() {
        for (var name : List.of("inks5-8.tif", "inks6-16a.tif")) {
            var kept = TiffDecoder.decode(MediaSource.of(vector(name)),
                    ImageDecoderOptions.builder().keepChannels(true).highBitDepth(true).build());
            var channels = assertInstanceOf(ChannelPicture.class, kept.picture());
            assertEquals(ColorModel.INK, channels.layout().colorModel());
            assertEquals("Cyan", channels.layout().channel(0).name());
            assertEquals("Orange", channels.layout().channel(4).name());
            var converted = ImageOps.toSrgb(kept).picture();
            var narrow = converted instanceof Rgba16Picture wide ? ImageOps.toEightBit(wide) : (RgbaPicture) converted;
            var direct = TiffDecoder.decode(MediaSource.of(vector(name)));
            assertArrayEquals(narrow.pixels(), ((RgbaPicture) direct.picture()).pixels(), name);
            var srgb = TiffDecoder.decode(MediaSource.of(vector(name)),
                    ImageDecoderOptions.builder().keepChannels(true).convertToSrgb(true).build());
            assertArrayEquals(((RgbaPicture) direct.picture()).pixels(), ((RgbaPicture) srgb.picture()).pixels(), name);
        }
    }

    @Test
    @DisplayName("named inks without a profile of as many channels are unsupported")
    void withoutProfile() {
        var values = TiffFiles.pattern(6, 4, 5, 255, 3);
        var bare = TiffFiles.of(6, 4, 5, 8).photometric(5).tag(332, 2).tag(334, 5)
                .data(TiffFiles.pack(values, 6, 4, 5, 8, true));
        var failure = assertThrows(MediaException.Unsupported.class,
                () -> TiffDecoder.decode(MediaSource.of(bare.bytes())));
        assertEquals("tiff ink set", failure.format());
        var mismatched = bare.undefined(34675, vector("ink6.icc")).bytes();
        failure = assertThrows(MediaException.Unsupported.class,
                () -> TiffDecoder.decode(MediaSource.of(mismatched)));
        assertEquals("tiff ink set", failure.format());
        var described = TiffDecoder.openPages(MediaSource.of(mismatched)).page(0);
        assertEquals(TiffPhotometric.SEPARATED, described.photometric());
        // the same samples with the five-ink profile decode
        var matched = TiffFiles.of(6, 4, 5, 8).photometric(5).tag(332, 2).tag(334, 5)
                .data(TiffFiles.pack(values, 6, 4, 5, 8, true)).undefined(34675, vector("ink5.icc")).bytes();
        assertInstanceOf(RgbaPicture.class, TiffDecoder.decode(MediaSource.of(matched)).picture());
    }

    @Test
    @DisplayName("damaged multi-ink files fail only with media exceptions")
    void damaged() {
        for (var name : List.of("inks5-8.tif", "inks6-16a.tif")) {
            var data = vector(name);
            for (var length = 8; length < data.length; length += 397) {
                var truncated = java.util.Arrays.copyOf(data, length);
                try {
                    TiffDecoder.decode(MediaSource.of(truncated));
                } catch (MediaException expected) {
                    // a truncated directory or profile is malformed or unsupported
                }
            }
        }
    }

    private static int u16(byte[] data, int sample) {
        return (data[sample * 2] & 0xFF) | (data[sample * 2 + 1] & 0xFF) << 8;
    }
}
