package com.github.auties00.smalt.audio.aac.internal.mps;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks where the conversion of residual MDCT spectra puts each spectral line in the QMF domain of the 128-band bank,
 * whose residual frames reach 4096 QMF samples, beyond the two AAC frames of the 64-band bank. A line of a window of
 * {@code m} QMF slots per subband lands in subband {@code line / m} and its neighbor, and nothing reaches the subbands
 * above the window's lines. The bit-exact comparison with the reference decoder is {@code MpegSurroundOracleTest}.
 */
class MpsMdctToQmfTest {
    private static final int BANDS = 128;

    private static double[] subbandEnergy(float[][] real, float[][] imag) {
        var energy = new double[BANDS];
        for (var slot = 0; slot < real.length; slot++) {
            for (var band = 0; band < BANDS; band++) {
                double re = real[slot][band];
                double im = imag[slot][band];
                energy[band] += re * re + im * im;
            }
        }
        return energy;
    }

    private static int loudest(double[] energy) {
        var best = 0;
        for (var band = 1; band < energy.length; band++) {
            if (energy[band] > energy[best]) {
                best = band;
            }
        }
        return best;
    }

    @ParameterizedTest(name = "{0} slots, window {1}, line {2}")
    @CsvSource({"32, 0, 0", "32, 0, 517", "32, 0, 1023", "32, 1, 300", "32, 3, 700", "30, 0, 1000", "24, 0, 811",
            "24, 3, 42", "18, 0, 1020", "18, 1, 600", "16, 0, 777", "15, 0, 1019"})
    @DisplayName("puts a line of a long window in its subband, nothing above the 1024 lines")
    void longWindow(int update, int window, int line) {
        var converter = new MpsMdctToQmf(BANDS);
        var mdct = new float[Math.max(2048, update * BANDS)];
        mdct[line] = 1000.0f;
        // a value past the 1024 lines that the conversion must replace with a zero line
        mdct[mdct.length - 1] = 1.0e6f;
        var real = new float[2 * update][BANDS];
        var imag = new float[2 * update][BANDS];
        converter.process(update, mdct, real, imag, window, 0);
        var energy = subbandEnergy(real, imag);
        var expected = line / update;
        var found = loudest(energy);
        assertTrue(found == expected || found == expected + 1 || found == expected - 1,
                "line " + line + " in subband " + found + ", expected " + expected);
        assertTrue(energy[expected] > 0.0);
        for (var band = 1023 / update + 2; band < BANDS; band++) {
            assertEquals(0.0, energy[band], 0.0, "subband " + band);
        }
    }

    @ParameterizedTest(name = "{0} slots, line {1}")
    @CsvSource({"32, 5", "32, 127", "16, 64", "16, 126", "18, 90", "24, 33", "30, 120", "15, 100"})
    @DisplayName("fills the upper subbands of a short window with nothing, also after a long window reached them")
    void shortWindow(int update, int line) {
        var converter = new MpsMdctToQmf(BANDS);
        var long15 = new float[2048];
        for (var i = 0; i < 1024; i++) {
            long15[i] = (float) Math.sin(0.37 * i) * 500.0f;
        }
        converter.process(15, long15, new float[30][BANDS], new float[30][BANDS], 0, 0);
        var mdct = new float[Math.max(2048, update * BANDS)];
        mdct[line] = 1000.0f;
        var real = new float[2 * update][BANDS];
        var imag = new float[2 * update][BANDS];
        converter.process(update, mdct, real, imag, 2, 0);
        var energy = subbandEnergy(real, imag);
        var linesPerSubband = update == 16 || update == 32 ? update / 8 : 2;
        var expected = line / linesPerSubband;
        var found = loudest(energy);
        assertTrue(Math.abs(found - expected) <= 1, "line " + line + " in subband " + found + ", expected " + expected);
        for (var band = 127 / linesPerSubband + 2; band < BANDS; band++) {
            assertEquals(0.0, energy[band], 0.0, "subband " + band);
        }
    }

    @Test
    @DisplayName("converts every residual frame of the 128-band bank without leaving the buffers of 4096 samples")
    void everyUpdate() {
        var converter = new MpsMdctToQmf(BANDS);
        for (var update : new int[]{15, 16, 18, 24, 30, 32}) {
            for (var window = 0; window < 4; window++) {
                var mdct = new float[Math.max(2048, update * BANDS)];
                for (var i = 0; i < 2048; i++) {
                    mdct[i] = (float) Math.cos(0.11 * i) * 100.0f;
                }
                var real = new float[4 * update][BANDS];
                var imag = new float[4 * update][BANDS];
                converter.process(update, mdct, real, imag, window, update);
                var energy = subbandEnergy(real, imag);
                var total = 0.0;
                for (var value : energy) {
                    assertTrue(Double.isFinite(value));
                    total += value;
                }
                assertTrue(total > 0.0, update + " slots, window " + window);
            }
        }
    }
}
