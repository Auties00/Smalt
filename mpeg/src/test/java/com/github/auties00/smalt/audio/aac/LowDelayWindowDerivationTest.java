package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.AacKernels;
import com.github.auties00.smalt.audio.aac.internal.AudioSpecificConfig;
import com.github.auties00.smalt.audio.aac.internal.ChannelStream;

import com.github.auties00.smalt.audio.aac.internal.LowDelayWindows;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repeats the derivation of the AAC-ELD synthesis windows from mono ELD streams and their reference waveforms by
 * least squares, and checks that it gives the tables of the decoder.
 *
 * <p>The decoder output is linear in the window: every output sample {@code n} of frame {@code i} is
 * {@code sum(k) w[n + N/4 + kN] * z[i - k][n + N/4 + kN]}, where {@code z} is the extended DCT-IV of each frame's
 * spectrum, computed here in double precision. Each sample position therefore gives a system of three or four
 * unknowns with one equation per frame, solved through its normal equations; samples near full scale, which the
 * reference clips, are left out.
 */
class LowDelayWindowDerivationTest {
    /** Fits the window of one stream. */
    private static double[] fit(String stream) {
        var parsed = AacTestSupport.parseMp4(Fixtures.read(ErrorResilientVectors.fetch(stream + "_ep0.mp4")));
        var reference = AacTestSupport.readWav(Fixtures.read(ErrorResilientVectors.fetch(stream + ".wav")))[0];
        return fit(parsed.config(), parsed.frames(), reference);
    }

    @Test
    @DisplayName("the 480-sample window is the mean least-squares fit of three 480-sample streams")
    void window480() {
        var fits = new double[][]{fit("er_eld1100np_48"), fit("er_eld1101np_48"), fit("er_eld1110np_48")};
        var table = LowDelayWindows.window(480);
        assertEquals(4 * 480, table.length);
        double worst = 0;
        double spread = 0;
        for (var i = 0; i < table.length; i++) {
            var mean = (fits[0][i] + fits[1][i] + fits[2][i]) / 3;
            worst = Math.max(worst, Math.abs(mean - table[i]));
            for (var fit : fits) {
                spread = Math.max(spread, Math.abs(fit[i] - mean));
            }
        }
        assertTrue(worst < 1e-6, "largest deviation from the table " + worst);
        // the three streams agree with each other, so the fit is determined by the decoder, not the content
        assertTrue(spread < 1e-4, "largest deviation of a single fit from the mean " + spread);
    }

    @Test
    @DisplayName("a fit of a 512-sample stream reproduces the 512-sample window")
    void window512() {
        var fit = fit("er_eld1000np_48");
        var table = LowDelayWindows.window(512);
        double worst = 0;
        for (var i = 0; i < table.length; i++) {
            worst = Math.max(worst, Math.abs(fit[i] - table[i]));
        }
        assertTrue(worst < 1e-4, "largest deviation from the table " + worst);
    }

    /**
     * Fits the window.
     *
     * @param config the stream's AudioSpecificConfig
     * @param frames the access units
     * @param reference the reference waveform, aligned with the decoder output
     * @return the window, {@code 4N} values with the first {@code N/4} zero
     */
    private static double[] fit(byte[] config, List<byte[]> frames, float[] reference) {
        var asc = AudioSpecificConfig.parse(config);
        var n = asc.frameLength();
        var delay = n / 4;
        var stream = new ChannelStream(n, asc.tableIndex(), asc.objectType(), asc.resilience(), AacKernels.INSTANCE);
        var cosine = new double[n * n];
        for (var m = 0; m < n; m++) {
            for (var k = 0; k < n; k++) {
                cosine[m * n + k] = Math.cos(Math.PI / n * (m + 0.5) * (k + 0.5)) / (32768.0 * n);
            }
        }
        var history = new double[4][4 * n];
        var normal = new double[n][4 * 4];
        var right = new double[n][4];
        var y = new double[n];
        for (var frame = 0; frame < frames.size(); frame++) {
            var data = frames.get(frame);
            var reader = new AacBitReader(data);
            stream.parse(reader, false, false);
            stream.applyTns();
            var spectrum = stream.spectrum();
            for (var m = 0; m < n; m++) {
                double sum = 0;
                for (var k = 0; k < n; k++) {
                    sum += cosine[m * n + k] * spectrum[k];
                }
                y[m] = sum;
            }
            var z = history[frame & 3];
            for (var t = 0; t < 4 * n; t++) {
                var m = (t + 3 * n / 2) % (4 * n);
                z[t] = m < n ? y[m] : m < 2 * n ? -y[2 * n - 1 - m] : m < 3 * n ? -y[m - 2 * n] : y[4 * n - 1 - m];
            }
            if (frame < 3) {
                continue;
            }
            for (var j = 0; j < n; j++) {
                var target = (frame) * n + j;
                if (target >= reference.length || Math.abs(reference[target]) > 0.99) {
                    continue;
                }
                var unknowns = j + delay + 3 * n < 4 * n ? 4 : 3;
                var row = new double[4];
                for (var k = 0; k < unknowns; k++) {
                    row[k] = history[(frame - k) & 3][j + delay + k * n];
                }
                for (var a = 0; a < unknowns; a++) {
                    for (var b = 0; b < unknowns; b++) {
                        normal[j][a * 4 + b] += row[a] * row[b];
                    }
                    right[j][a] += row[a] * reference[target];
                }
            }
        }
        var window = new double[4 * n];
        for (var j = 0; j < n; j++) {
            var unknowns = j + delay + 3 * n < 4 * n ? 4 : 3;
            var solution = solve(normal[j], right[j], unknowns);
            for (var k = 0; k < unknowns; k++) {
                window[j + delay + k * n] = solution[k];
            }
        }
        return window;
    }

    /**
     * Solves a small symmetric system by Gaussian elimination with partial pivoting.
     */
    private static double[] solve(double[] matrix, double[] vector, int size) {
        var a = new double[size][size + 1];
        for (var i = 0; i < size; i++) {
            for (var j = 0; j < size; j++) {
                a[i][j] = matrix[i * 4 + j];
            }
            a[i][size] = vector[i];
        }
        for (var column = 0; column < size; column++) {
            var pivot = column;
            for (var i = column + 1; i < size; i++) {
                if (Math.abs(a[i][column]) > Math.abs(a[pivot][column])) {
                    pivot = i;
                }
            }
            var swap = a[pivot];
            a[pivot] = a[column];
            a[column] = swap;
            for (var i = column + 1; i < size; i++) {
                var factor = a[i][column] / a[column][column];
                for (var j = column; j <= size; j++) {
                    a[i][j] -= factor * a[column][j];
                }
            }
        }
        var result = new double[size];
        for (var i = size - 1; i >= 0; i--) {
            var sum = a[i][size];
            for (var j = i + 1; j < size; j++) {
                sum -= a[i][j] * result[j];
            }
            result[i] = sum / a[i][i];
        }
        return result;
    }
}
