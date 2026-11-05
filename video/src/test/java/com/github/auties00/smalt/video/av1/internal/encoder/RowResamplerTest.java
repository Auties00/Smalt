package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the super-resolution downscaler and the whole-sample motion decision of the inter
 * encoders.
 */
class RowResamplerTest {
    @ParameterizedTest(name = "{0} to {1}")
    @CsvSource({"128, 64", "128, 102", "127, 64", "99, 55", "16, 16", "1000, 501"})
    @DisplayName("keeps a flat row flat and a ramp increasing, within the sample range")
    void shapes(int length, int outLength) {
        var resampler = new RowResampler();
        var flat = new short[length];
        java.util.Arrays.fill(flat, (short) 700);
        var out = new short[outLength + 2];
        resampler.resample(flat, 0, length, out, 1, outLength, 1023);
        for (var i = 1; i <= outLength; i++) {
            assertEquals(700, out[i], "flat sample " + i);
        }
        assertEquals(0, out[0], "nothing before the output");
        assertEquals(0, out[outLength + 1], "nothing after the output");
        var ramp = new short[length];
        for (var i = 0; i < length; i++) {
            ramp[i] = (short) (i * 4000 / length);
        }
        resampler.resample(ramp, 0, length, out, 0, outLength, 4095);
        // the edges repeat the end samples, where the lowpass may ring; the interior stays increasing
        for (var i = 5; i < outLength - 4; i++) {
            assertTrue(out[i] >= out[i - 1], "ramp sample " + i + ": " + out[i - 1] + " then " + out[i]);
        }
        assertTrue(out[0] >= 0 && out[outLength - 1] <= 4000);
    }

    @Test
    @DisplayName("halves an alternating row to its mean")
    void halving() {
        var row = new short[64];
        for (var i = 0; i < row.length; i++) {
            row[i] = (short) ((i & 1) != 0 ? 200 : 100);
        }
        var out = new short[32];
        new RowResampler().resample(row, 0, 64, out, 0, 32, 255);
        for (var i = 2; i < 30; i++) {
            assertEquals(150, out[i], 1, "sample " + i);
        }
    }

    static YuvPicture picture(int seed, boolean flatRows) {
        var picture = YuvPicture.allocate(64, 48, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
        var random = new SplittableRandom(seed);
        for (var y = 0; y < 48; y++) {
            var rowValue = random.nextInt(256);
            for (var x = 0; x < 64; x++) {
                picture.y()[y * picture.yStride() + x] = (byte) (flatRows ? rowValue : random.nextInt(256));
            }
        }
        return picture;
    }

    @Test
    @DisplayName("takes whole-sample motion for repeated and smooth pictures, not for changing texture")
    void integerMv() {
        var decision = new IntegerMvDecision(64, 48);
        decision.advance(picture(1, false));
        assertFalse(decision.decide(), "the first picture has no previous one");
        decision.advance(picture(1, false));
        assertTrue(decision.decide(), "a repeated picture");
        decision.advance(picture(2, true));
        assertTrue(decision.decide(), "a picture of constant rows, after a repeated one");
        decision.advance(picture(3, false));
        assertFalse(decision.decide(), "new texture");
    }
}
