/* Ports vo-aacenc 0.1.3 (aacenc/src/tns.c). Copyright 2003-2010 VisualOn, Inc.; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal;

import java.util.Arrays;

/**
 * Decides and applies temporal noise shaping for one window of an encoded channel.
 *
 * <p>The analysis whitens the spectrum band by band, fits a linear predictor across frequency
 * with the Levinson-Durbin recursion and enables TNS when the prediction gain exceeds 1.41. The
 * reflection coefficients are quantized exactly as the decoder dequantizes them (4 bits in long
 * windows, 3 bits in short ones), converted to a direct-form predictor with the decoder's
 * step-up recursion, and the analysis (FIR) filter {@code e[n] = x[n] + sum a[i] x[n - i]} runs
 * upwards over the TNS range, so the decoder's all-pole filter restores the spectrum.
 *
 * @implNote This implementation follows the TNS encoder of 3GPP TS 26.403: filter order 12 from 1275 Hz in
 * long windows and 5 from 2750 Hz in short ones, a prediction gain threshold of 1.41, and
 * trailing coefficients below 0.1 dropped.
 */
public final class TnsEncoder {
    /**
     * The largest filter order of long windows.
     */
    private static final int LONG_ORDER = 12;

    /**
     * The largest filter order of short windows.
     */
    private static final int SHORT_ORDER = 5;

    /**
     * The smallest prediction gain that enables TNS.
     */
    private static final double GAIN_THRESHOLD = 1.41;

    /**
     * The magnitude below which trailing reflection coefficients are dropped.
     */
    private static final double COEFFICIENT_THRESHOLD = 0.1;

    /**
     * The kernel computing the autocorrelation.
     */
    private final AacKernel kernel;

    /**
     * The first band of the TNS range.
     */
    private final int startBand;

    /**
     * The band after the last of the TNS range.
     */
    private final int stopBand;

    /**
     * The number of bands of the window size.
     */
    private final int bandCount;

    /**
     * The band offsets.
     */
    private final int[] offsets;

    /**
     * The largest filter order.
     */
    private final int maxOrder;

    /**
     * The coefficient resolution in bits.
     */
    private final int resolution;

    /**
     * The autocorrelation of the whitened spectrum.
     */
    private final double[] correlation = new double[LONG_ORDER + 1];

    /**
     * The predictor being built by the Levinson-Durbin recursion.
     */
    private final double[] predictor = new double[LONG_ORDER + 1];

    /**
     * The scratch copy of the predictor.
     */
    private final double[] scratch = new double[LONG_ORDER + 1];

    /**
     * The reflection coefficients.
     */
    private final double[] reflection = new double[LONG_ORDER];

    /**
     * The whitened spectrum of the analyzed window.
     */
    private final float[] whitened;

    /**
     * The direct-form coefficients of the quantized filter, {@code a[1..order]}.
     */
    private final float[] lpc = new float[LONG_ORDER + 1];

    /**
     * The filter state.
     */
    private final float[] history = new float[LONG_ORDER];

    /**
     * The dequantized reflection coefficients.
     */
    private final float[] parcor = new float[LONG_ORDER];

    /**
     * The scratch coefficients of the step-up recursion.
     */
    private final float[] stepScratch = new float[LONG_ORDER + 1];

    /**
     * Configures TNS for one window size.
     *
     * @param layout the band layout of the window size
     * @param sampleRate the sampling rate in hertz
     * @param shortWindow whether the layout is for short windows
     * @param kernel the kernel computing the autocorrelation
     */
    public TnsEncoder(BandLayout layout, int sampleRate, boolean shortWindow, AacKernel kernel) {
        this.kernel = kernel;
        this.offsets = layout.offsets();
        this.bandCount = layout.count();
        this.maxOrder = shortWindow ? SHORT_ORDER : LONG_ORDER;
        this.resolution = shortWindow ? 3 : 4;
        var tableIndex = SamplingTables.tableIndexFor(sampleRate);
        var windowLength = layout.windowLength();
        var maxBands = !shortWindow && (windowLength == 512 || windowLength == 480)
                ? SamplingTables.tnsMaxBandsLowDelay(tableIndex, windowLength)
                : SamplingTables.tnsMaxBands(tableIndex, shortWindow);
        var limit = Math.min(maxBands, layout.active());
        var startFrequency = shortWindow ? 2750 : 1275;
        var startLine = (int) Math.round(startFrequency * 2.0 * layout.windowLength() / sampleRate);
        var band = 0;
        while (band < bandCount && offsets[band + 1] <= startLine) {
            band++;
        }
        this.startBand = Math.min(band, limit);
        this.stopBand = limit;
        this.whitened = new float[layout.windowLength()];
    }

    /**
     * Returns the first band of the TNS range.
     *
     * @return the band index
     */
    public int startBand() {
        return startBand;
    }

    /**
     * Returns the band after the last of the TNS range; a channel using TNS must transmit at
     * least this many bands so that the decoder filters the same range.
     *
     * @return the band index
     */
    public int stopBand() {
        return stopBand;
    }

    /**
     * Returns the value of the {@code length} field: the bands from the TNS start to the top of
     * the window size.
     *
     * @return the length in bands
     */
    public int length() {
        return bandCount - startBand;
    }

    /**
     * Returns the coefficient resolution in bits.
     *
     * @return 4 for long windows, 3 for short windows
     */
    public int resolution() {
        return resolution;
    }

    /**
     * Analyzes one window and, when TNS pays off, filters it in place.
     *
     * @param spectrum the spectral values
     * @param offset the index of the window's first line
     * @param indexes receives the quantized reflection coefficients
     * @return the filter order, 0 when TNS stays off
     */
    public int apply(float[] spectrum, int offset, int[] indexes) {
        if (stopBand - startBand < 2) {
            return 0;
        }
        var start = offsets[startBand];
        var end = offsets[stopBand];
        if (end - start <= maxOrder * 2) {
            return 0;
        }
        whiten(spectrum, offset, start, end);
        var gain = levinson(start, end);
        if (gain <= GAIN_THRESHOLD) {
            return 0;
        }
        var order = maxOrder;
        while (order > 0 && Math.abs(reflection[order - 1]) < COEFFICIENT_THRESHOLD) {
            order--;
        }
        if (order == 0) {
            return 0;
        }
        var positive = ((1 << (resolution - 1)) - 0.5) / (Math.PI / 2);
        var negative = ((1 << (resolution - 1)) + 0.5) / (Math.PI / 2);
        var low = -(1 << (resolution - 1));
        var high = (1 << (resolution - 1)) - 1;
        for (var i = 0; i < order; i++) {
            var value = reflection[i];
            var best = 0;
            var bestError = Double.MAX_VALUE;
            for (var index = low; index <= high; index++) {
                var reconstructed = StrictMath.sin(index / (index >= 0 ? positive : negative));
                var error = Math.abs(reconstructed - value);
                if (error < bestError) {
                    bestError = error;
                    best = index;
                }
            }
            indexes[i] = best;
            parcor[i] = (float) StrictMath.sin(best / (best >= 0 ? positive : negative));
        }
        stepUp(order);
        filter(spectrum, offset + start, end - start, order);
        return order;
    }

    /**
     * Normalizes each band of the TNS range to unit energy, smoothing the gains across band
     * borders.
     *
     * @param spectrum the spectral values
     * @param offset the index of the window's first line
     * @param start the first line of the range
     * @param end the line after the last
     */
    private void whiten(float[] spectrum, int offset, int start, int end) {
        for (var band = startBand; band < stopBand; band++) {
            var energy = 0.0;
            for (var k = offsets[band]; k < offsets[band + 1]; k++) {
                energy += (double) spectrum[offset + k] * spectrum[offset + k];
            }
            var gain = energy > 1e-9 ? (float) (1 / Math.sqrt(energy)) : 0;
            for (var k = offsets[band]; k < offsets[band + 1]; k++) {
                whitened[k] = gain;
            }
        }
        for (var k = end - 2; k >= start; k--) {
            whitened[k] = (whitened[k] + whitened[k + 1]) * 0.5f;
        }
        for (var k = start + 1; k < end; k++) {
            whitened[k] = (whitened[k] + whitened[k - 1]) * 0.5f;
        }
        for (var k = start; k < end; k++) {
            whitened[k] *= spectrum[offset + k];
        }
    }

    /**
     * Fits the predictor to the whitened range and returns the prediction gain.
     *
     * @param start the first line
     * @param end the line after the last
     * @return the prediction gain, 0 when the range is silent
     */
    private double levinson(int start, int end) {
        for (var lag = 0; lag <= maxOrder; lag++) {
            correlation[lag] = kernel.dot(whitened, start, whitened, start + lag, end - start - lag);
        }
        if (correlation[0] <= 0) {
            return 0;
        }
        var error = correlation[0];
        predictor[0] = 1;
        for (var m = 1; m <= maxOrder; m++) {
            var acc = correlation[m];
            for (var i = 1; i < m; i++) {
                acc += predictor[i] * correlation[m - i];
            }
            var k = -acc / error;
            if (Math.abs(k) >= 1) {
                for (var i = m; i <= maxOrder; i++) {
                    reflection[i - 1] = 0;
                }
                break;
            }
            reflection[m - 1] = k;
            for (var i = 1; i < m; i++) {
                scratch[i] = predictor[i] + k * predictor[m - i];
            }
            for (var i = 1; i < m; i++) {
                predictor[i] = scratch[i];
            }
            predictor[m] = k;
            error *= 1 - k * k;
            if (error <= 0) {
                return 0;
            }
        }
        return correlation[0] / error;
    }

    /**
     * Converts the dequantized reflection coefficients to direct-form coefficients with the
     * recursion the decoder uses.
     *
     * @param order the filter order
     */
    private void stepUp(int order) {
        var tmp = stepScratch;
        lpc[0] = 1;
        for (var m = 1; m <= order; m++) {
            for (var i = 1; i < m; i++) {
                tmp[i] = lpc[i] + parcor[m - 1] * lpc[m - i];
            }
            for (var i = 1; i < m; i++) {
                lpc[i] = tmp[i];
            }
            lpc[m] = parcor[m - 1];
        }
    }

    /**
     * Runs the analysis filter upwards over a run of lines.
     *
     * @param spectrum the spectral values, in place
     * @param first the index of the first line
     * @param size the number of lines
     * @param order the filter order
     */
    private void filter(float[] spectrum, int first, int size, int order) {
        Arrays.fill(history, 0);
        for (var n = 0; n < size; n++) {
            var x = spectrum[first + n];
            var y = x;
            for (var i = 0; i < order; i++) {
                y += lpc[i + 1] * history[i];
            }
            for (var i = order - 1; i > 0; i--) {
                history[i] = history[i - 1];
            }
            history[0] = x;
            spectrum[first + n] = y;
        }
    }
}
