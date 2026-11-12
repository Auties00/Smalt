package com.github.auties00.smalt.image.webp.internal.color;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.YuvPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the sharp RGB to Y'CbCr conversion and the dithered conversion: the gamma tables against
 * values computed independently in high precision, the fallback for tiny pictures, gray input,
 * alpha handling, and the bounded, deterministic effect of dithering. Bit-exactness against libwebp
 * and thread-count invariance are covered end to end by the encoder's cwebp comparisons and the
 * {@code it} module's thread digests.
 */
@DisplayName("Sharp and dithered RGB to Y'CbCr")
class SharpYuvTest {
    static byte[] random(int width, int height, long seed, boolean alpha) {
        var rgba = new byte[width * height * 4];
        new Random(seed).nextBytes(rgba);
        if (!alpha) {
            for (var i = 3; i < rgba.length; i += 4) {
                rgba[i] = (byte) 255;
            }
        }
        return rgba;
    }

    static byte[] planes(YuvPicture picture) {
        var w = picture.width();
        var h = picture.height();
        var cw = (w + 1) / 2;
        var ch = (h + 1) / 2;
        var out = new byte[w * h + 2 * cw * ch + (picture.alpha() == null ? 0 : w * h)];
        for (var y = 0; y < h; y++) {
            System.arraycopy(picture.y(), y * picture.yStride(), out, y * w, w);
        }
        for (var y = 0; y < ch; y++) {
            System.arraycopy(picture.u(), y * picture.uvStride(), out, w * h + y * cw, cw);
            System.arraycopy(picture.v(), y * picture.uvStride(), out, w * h + cw * ch + y * cw, cw);
        }
        if (picture.alpha() != null) {
            for (var y = 0; y < h; y++) {
                System.arraycopy(picture.alpha(), y * picture.alphaStride(), out, w * h + 2 * cw * ch + y * w, w);
            }
        }
        return out;
    }

    @Test
    @DisplayName("the gamma tables equal libwebp's, computed in 60-digit precision")
    void gammaTables() throws Exception {
        var buffer = ByteBuffer.allocate(4 * (SharpYuv.GAMMA_TO_LINEAR.length + SharpYuv.LINEAR_TO_GAMMA.length))
                .order(ByteOrder.LITTLE_ENDIAN);
        for (var v : SharpYuv.GAMMA_TO_LINEAR) {
            buffer.putInt(v);
        }
        for (var v : SharpYuv.LINEAR_TO_GAMMA) {
            buffer.putInt(v);
        }
        // SHA-256 of the 1026 + 514 entries computed with Python's decimal module at 60 digits from
        // the double inputs of sharpyuv_gamma.c; the nearest entry lies 4e-5 from a rounding tie
        assertEquals("b2ee8e4f86b817ea39fde78c47250ef88b08be6754c34ebdbae0e24535345ec8",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.array())));
        assertEquals(65536, SharpYuv.GAMMA_TO_LINEAR[1024]);
        assertEquals(14, SharpYuv.GAMMA_TO_LINEAR[1]);
        assertEquals(576, SharpYuv.LINEAR_TO_GAMMA[1]);
    }

    @ParameterizedTest(name = "{0}x{1}")
    @CsvSource({"3,3", "1,9", "9,2", "3,100"})
    @DisplayName("pictures under 4 pixels on a side use the plain conversion")
    void tiny(int width, int height) {
        var rgba = random(width, height, width * 7L + height, true);
        assertArrayEquals(planes(YuvConverter.fromRgba(rgba, width * 4, width, height, true, MediaLimits.DEFAULT)),
                planes(SharpYuv.fromRgba(rgba, width * 4, width, height, true, MediaLimits.DEFAULT)));
    }

    @Test
    @DisplayName("gray input gives neutral chroma and the luma of the plain conversion")
    void gray() {
        var width = 34;
        var height = 21;
        var rgba = new byte[width * height * 4];
        for (var i = 0; i < width * height; i++) {
            var v = (byte) (i * 7);
            rgba[4 * i] = v;
            rgba[4 * i + 1] = v;
            rgba[4 * i + 2] = v;
            rgba[4 * i + 3] = (byte) 255;
        }
        var sharp = SharpYuv.fromRgba(rgba, width * 4, width, height, true, MediaLimits.DEFAULT);
        assertNull(sharp.alpha());
        for (var j = 0; j < (height + 1) / 2; j++) {
            for (var i = 0; i < (width + 1) / 2; i++) {
                assertEquals(128, sharp.u()[j * sharp.uvStride() + i] & 0xFF, "U at " + i + "," + j);
                assertEquals(128, sharp.v()[j * sharp.uvStride() + i] & 0xFF, "V at " + i + "," + j);
            }
        }
        var plain = YuvConverter.fromRgba(rgba, width * 4, width, height, true, MediaLimits.DEFAULT);
        for (var i = 0; i < width * height; i++) {
            var diff = Math.abs((sharp.y()[i] & 0xFF) - (plain.y()[i] & 0xFF));
            assertTrue(diff <= 1, "luma " + i + " differs by " + diff);
        }
    }

    @Test
    @DisplayName("alpha is copied as is and does not weigh the chroma")
    void alpha() {
        var width = 40;
        var height = 30;
        var rgba = random(width, height, 11, true);
        var opaque = rgba.clone();
        for (var i = 3; i < opaque.length; i += 4) {
            opaque[i] = (byte) 255;
        }
        var withAlpha = SharpYuv.fromRgba(rgba, width * 4, width, height, true, MediaLimits.DEFAULT);
        var without = SharpYuv.fromRgba(opaque, width * 4, width, height, true, MediaLimits.DEFAULT);
        assertNotNull(withAlpha.alpha());
        assertNull(without.alpha());
        for (var i = 0; i < width * height; i++) {
            assertEquals(rgba[4 * i + 3], withAlpha.alpha()[i]);
        }
        var a = planes(withAlpha);
        var b = planes(without);
        assertArrayEquals(java.util.Arrays.copyOf(b, b.length), java.util.Arrays.copyOf(a, b.length));
    }

    @Test
    @DisplayName("dithering moves samples by at most one level, is deterministic and differs from plain rounding")
    void dithering() {
        var width = 64;
        var height = 47;
        var rgba = random(width, height, 9, true);
        var plain = planes(YuvConverter.fromRgba(rgba, width * 4, width, height, true, MediaLimits.DEFAULT));
        var dithered = planes(YuvConverter.fromRgbaDithered(rgba, width * 4, width, height, true, 1f, MediaLimits.DEFAULT));
        assertArrayEquals(dithered, planes(YuvConverter.fromRgbaDithered(rgba, width * 4, width, height, true, 1f,
                MediaLimits.DEFAULT)));
        assertArrayEquals(plain, planes(YuvConverter.fromRgbaDithered(rgba, width * 4, width, height, true, 0f,
                MediaLimits.DEFAULT)));
        var changed = 0;
        for (var i = 0; i < plain.length; i++) {
            var diff = Math.abs((plain[i] & 0xFF) - (dithered[i] & 0xFF));
            assertTrue(diff <= 1, "sample " + i + " differs by " + diff);
            changed += diff;
        }
        assertTrue(changed > plain.length / 10, "only " + changed + " samples changed");
    }
}
