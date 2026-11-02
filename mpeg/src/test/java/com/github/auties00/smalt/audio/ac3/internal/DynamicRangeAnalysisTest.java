package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.audio.ac3.Ac3DynamicRangeProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the quantization of the dynamic range control words against the gains A/52 sections
 * 7.7.1.2 and 7.7.2.2 give them, and the time behavior of the line mode gain.
 */
class DynamicRangeAnalysisTest {
    private static double dynamicRangeGain(int word) {
        var exponent = ((byte) word) >> 5;
        var mantissa = word & 0x1F;
        return 20 * Math.log10(Math.scalb((32 + mantissa) / 64.0, exponent + 1));
    }

    private static double compressionGain(int word) {
        var exponent = ((byte) word) >> 4;
        var mantissa = word & 0x0F;
        return 20 * Math.log10(Math.scalb((16 + mantissa) / 32.0, exponent + 1));
    }

    @Test
    @DisplayName("quantize a gain to the closest word at or below it")
    void words() {
        for (var decibels = -24.0; decibels <= 23.9; decibels += 0.05) {
            var word = DynamicRangeAnalysis.dynamicRangeWord(decibels);
            var gain = dynamicRangeGain(word);
            assertTrue(gain <= decibels + 1e-6 && gain > decibels - 0.28, decibels + " dB -> " + gain);
        }
        for (var decibels = -48.0; decibels <= 47.8; decibels += 0.05) {
            var word = DynamicRangeAnalysis.compressionWord(decibels);
            assertTrue(word >= 0 && word < 256);
            var gain = compressionGain(word);
            assertTrue(gain <= decibels + 1e-6 && gain > decibels - 0.54, decibels + " dB -> " + gain);
        }
        assertEquals(0, DynamicRangeAnalysis.dynamicRangeWord(0));
        assertEquals(-24.08, dynamicRangeGain(DynamicRangeAnalysis.dynamicRangeWord(-40)), 0.01);
        assertEquals(23.95, dynamicRangeGain(DynamicRangeAnalysis.dynamicRangeWord(40)), 0.01);
        assertEquals(-48.16, compressionGain(DynamicRangeAnalysis.compressionWord(-60)), 0.01);
    }

    @Test
    @DisplayName("cut at once and release over a second")
    void timing() {
        var analysis = new DynamicRangeAnalysis(Ac3DynamicRangeProfile.FILM_STANDARD, Ac3DynamicRangeProfile.NONE, -31,
                48000, 6);
        var quiet = new float[1][1536];
        var loud = new float[1][1536];
        for (var i = 0; i < 1536; i++) {
            quiet[0][i] = (float) (0.0005 * Math.sin(i * 0.1));
            loud[0][i] = (float) (0.9 * Math.sin(i * 0.1));
        }
        var channels = new int[]{0};
        analysis.analyze(quiet, channels, 0);
        assertEquals(6, dynamicRangeGain(analysis.words()[5]), 0.3);
        assertEquals(FrameEncoder.NO_WORD, analysis.compression());
        analysis.analyze(loud, channels, 0);
        var cut = dynamicRangeGain(analysis.words()[0]);
        assertTrue(cut < -15, "cut " + cut);
        // a second of quiet input releases most of the cut
        var released = cut;
        for (var frame = 0; frame < 31; frame++) {
            analysis.analyze(quiet, channels, 0);
            released = dynamicRangeGain(analysis.words()[5]);
        }
        assertTrue(released > cut + 0.55 * (6 - cut) && released < 6, "released to " + released);
    }
}
