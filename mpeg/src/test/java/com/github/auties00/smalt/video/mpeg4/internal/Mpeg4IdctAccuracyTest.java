package com.github.auties00.smalt.video.mpeg4.internal;

import com.github.auties00.smalt.testkit.Ieee1180;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the IEEE 1180-1990 accuracy test on the inverse DCT that the MPEG-4 Part 2 and H.263 decoders share: ISO/IEC
 * 14496-2 Annex A and ITU-T H.263 Annex A leave the transform to the decoder within that accuracy, which is what makes
 * the differential tests' tolerance against ffmpeg's other inverse DCTs legitimate.
 */
@DisplayName("MPEG-4 Part 2 and H.263 inverse DCT accuracy")
class Mpeg4IdctAccuracyTest {
    /** Transposes the test's row-major coefficients into the kernels' column-major blocks. */
    private static short[] block(int[] coefficients) {
        var block = new short[64];
        for (var v = 0; v < 8; v++) {
            for (var u = 0; u < 8; u++) {
                block[u * 8 + v] = (short) coefficients[v * 8 + u];
            }
        }
        return block;
    }

    @Test
    @DisplayName("the reference transform meets IEEE 1180 in all six runs and maps zeros to zeros")
    void scalar() {
        var runs = Ieee1180.assertCompliant("ScalarMpeg4Dsp.transform",
                (coefficients, samples) -> ScalarMpeg4Dsp.transform(block(coefficients), 0, samples));
        runs.forEach(run -> System.out.println("[ieee1180] " + run));
    }

    @Test
    @DisplayName("the selected kernels reconstruct every IEEE 1180 block as the reference transform does")
    void selected() {
        var dsp = Mpeg4Dsp.select();
        var work = new int[Mpeg4Dsp.SCRATCH_INTS];
        var reference = new int[64];
        var low = new byte[64];
        var high = new byte[64];
        for (var range : new int[][]{{256, 255}, {5, 5}, {300, 300}}) {
            Ieee1180.measure((coefficients, samples) -> {
                ScalarMpeg4Dsp.transform(block(coefficients), 0, reference);
                java.util.Arrays.fill(low, (byte) 0);
                java.util.Arrays.fill(high, (byte) 255);
                dsp.idctAdd(block(coefficients), 0, low, 0, 8, work);
                dsp.idctAdd(block(coefficients), 0, high, 0, 8, work);
                for (var i = 0; i < 64; i++) {
                    // a residual r adds to a prediction of 0 as max(r, 0) and to one of 255 as min(255 + r, 255)
                    var expectedLow = Math.max(0, Math.min(255, reference[i]));
                    var expectedHigh = Math.max(0, Math.min(255, 255 + reference[i]));
                    assertEquals(expectedLow, low[i] & 0xFF, "sample " + i + " over a prediction of 0");
                    assertEquals(expectedHigh, high[i] & 0xFF, "sample " + i + " over a prediction of 255");
                    samples[i] = reference[i];
                }
            }, range[0], range[1], false, 2000);
        }
    }
}
