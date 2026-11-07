package com.github.auties00.smalt.audio.g722;

import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Runs the digital test sequences of ITU-T G.722 Appendix II through the sub-band coders, which the sequences
 * test with the quadrature mirror filters bypassed: the encoder sequences must code to the reference codes, and the
 * reference codes must decode in the lower sub-band, in each of the three modes, and in the higher sub-band to the
 * reference reconstructed signals. A word with its lowest bit set resets both coders, as the sequences signal it.
 */
class G722ConformanceTest {
    /** The test sequences check the codes and reconstructed signals bit for bit. */
    private static final Tolerance TOLERANCE = Tolerance.exact();

    private static int[] sequence(String name) {
        return G722Sequences.words(name);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"T1C1.XMT, T2R1.COD", "T1C2.XMT, T2R2.COD"})
    @DisplayName("codes the encoder sequences to the reference codes")
    void encoder(String input, String reference) {
        var x = sequence(input);
        var expected = sequence(reference);
        var low = new G722Band(true);
        var high = new G722Band(false);
        var codes = new int[x.length];
        for (var i = 0; i < x.length; i++) {
            if ((x[i] & 1) != 0) {
                low.reset();
                high.reset();
                codes[i] = 1;
                continue;
            }
            var xl = (short) x[i] >> 1;
            var il = low.encodeLow(xl);
            var ih = high.encodeHigh(xl);
            codes[i] = ((ih << 6) + il) << 8;
        }
        TOLERANCE.assertWithin("G.722 " + input + " codes", expected, codes);
    }

    @ParameterizedTest(name = "{0} mode {3}")
    @CsvSource({"T2R1.COD, T3L1.RC, T3H1.RC0, 1", "T2R1.COD, T3L1.RC, T3H1.RC0, 2", "T2R1.COD, T3L1.RC, T3H1.RC0, 3",
            "T2R2.COD, T3L2.RC, T3H2.RC0, 1", "T2R2.COD, T3L2.RC, T3H2.RC0, 2", "T2R2.COD, T3L2.RC, T3H2.RC0, 3",
            "T1D3.COD, T3L3.RC, T3H3.RC0, 1", "T1D3.COD, T3L3.RC, T3H3.RC0, 2", "T1D3.COD, T3L3.RC, T3H3.RC0, 3"})
    @DisplayName("decodes the reference codes to the reference sub-band signals")
    void decoder(String input, String lowPrefix, String highReference, int mode) {
        var codes = sequence(input);
        var expectedLow = sequence(lowPrefix + mode);
        var low = new G722Band(true);
        var lowOut = new int[codes.length];
        for (var i = 0; i < codes.length; i++) {
            if ((codes[i] & 1) != 0) {
                low.reset();
                lowOut[i] = 1;
            } else {
                lowOut[i] = (low.decodeLow((codes[i] >> 8) & 63, mode) << 1) & 0xFFFF;
            }
        }
        TOLERANCE.assertWithin("G.722 " + input + " lower sub-band, mode " + mode, expectedLow, lowOut);
        if (mode == 1) {
            var expectedHigh = sequence(highReference);
            var high = new G722Band(false);
            var highOut = new int[codes.length];
            for (var i = 0; i < codes.length; i++) {
                if ((codes[i] & 1) != 0) {
                    high.reset();
                    highOut[i] = 1;
                } else {
                    highOut[i] = (high.decodeHigh((codes[i] >> 14) & 3) << 1) & 0xFFFF;
                }
            }
            TOLERANCE.assertWithin("G.722 " + input + " higher sub-band", expectedHigh, highOut);
        }
    }
}
