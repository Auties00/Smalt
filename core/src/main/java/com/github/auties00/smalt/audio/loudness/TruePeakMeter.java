/* Ports libebur128 1.2.6 (ebur128/ebur128.c). Copyright Jan Kokemueller; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.audio.loudness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Measures the true peak of each channel: the largest magnitude of the signal oversampled four
 * times (twice from 96 kHz, not at all from 192 kHz), ITU-R BS.1770-4 Annex 2.
 *
 * <p>The oversampling filter is the 49-tap Hann-windowed sinc of libebur128, split into one
 * polyphase sub-filter per output phase; the phase that reproduces the input samples is skipped,
 * since the meter reports the larger of the true peak and the sample peak anyway.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation keeps the taps, window and oversampling factors of libebur128's
 * {@code interp_create} and {@code interp_process} but accumulates in single precision with fused
 * multiply-adds instead of double precision, a difference far below the 0.1 dB a true-peak reading
 * is reported with.
 */
final class TruePeakMeter {
    /**
     * The number of taps of the oversampling filter, before the polyphase split.
     */
    private static final int TAPS = 49;

    /**
     * The number of input samples processed per kernel call.
     */
    private static final int CHUNK = 4096;

    /**
     * The coefficients below which a tap is dropped, as libebur128 does.
     */
    private static final double ALMOST_ZERO = 0.000001;

    /**
     * Holds the polyphase sub-filters of one oversampling factor.
     *
     * @param coefficients the coefficients, {@code taps} per phase
     * @param offsets the index of the input sample each coefficient multiplies, relative to
     * the output index in a buffer that starts with {@code history} samples
     * @param phases the number of phases kept
     * @param taps the number of taps of each phase
     * @param history the number of past samples a block needs
     */
    record Filter(float[] coefficients, int[] offsets, int phases, int taps, int history) {
    }

    /**
     * The filter, or {@code null} when the rate needs no oversampling.
     */
    private final Filter filter;

    /**
     * The last {@code history} input samples of each channel.
     */
    private final float[][] histories;

    /**
     * The buffer holding a channel's history followed by a chunk of input.
     */
    private final float[] work;

    /**
     * The kernel.
     */
    private final TruePeakKernel kernel;

    /**
     * Constructs a meter.
     *
     * @param channels the channel count
     * @param sampleRate the sampling rate in hertz
     */
    TruePeakMeter(int channels, int sampleRate) {
        this.filter = filter(sampleRate);
        this.kernel = TruePeakKernel.SELECTED;
        var history = filter == null ? 0 : filter.history();
        this.histories = new float[channels][history];
        this.work = new float[history + CHUNK];
    }

    /**
     * Builds the polyphase sub-filters for a sampling rate.
     *
     * @param sampleRate the sampling rate in hertz
     * @return the sub-filters, or {@code null} at 192 kHz and above
     */
    static Filter filter(int sampleRate) {
        int factor;
        if (sampleRate < 96_000) {
            factor = 4;
        } else if (sampleRate < 192_000) {
            factor = 2;
        } else {
            return null;
        }
        var delay = (TAPS + factor - 1) / factor;
        var phaseCoefficients = new ArrayList<List<Double>>();
        var phaseIndexes = new ArrayList<List<Integer>>();
        for (var phase = 0; phase < factor; phase++) {
            phaseCoefficients.add(new ArrayList<>());
            phaseIndexes.add(new ArrayList<>());
        }
        for (var j = 0; j < TAPS; j++) {
            var m = j - (TAPS - 1) / 2.0;
            var c = 1.0;
            if (Math.abs(m) > ALMOST_ZERO) {
                c = Math.sin(m * Math.PI / factor) / (m * Math.PI / factor);
            }
            c *= 0.5 * (1 - Math.cos(2 * Math.PI * j / (TAPS - 1)));
            if (Math.abs(c) > ALMOST_ZERO) {
                phaseCoefficients.get(j % factor).add(c);
                phaseIndexes.get(j % factor).add(j / factor);
            }
        }
        var kept = new ArrayList<Integer>();
        var taps = 0;
        for (var phase = 0; phase < factor; phase++) {
            var coefficients = phaseCoefficients.get(phase);
            if (coefficients.isEmpty() || coefficients.size() == 1 && coefficients.getFirst() == 1.0) {
                continue;
            }
            kept.add(phase);
            taps = Math.max(taps, coefficients.size());
        }
        var history = delay - 1;
        var coefficients = new float[kept.size() * taps];
        var offsets = new int[kept.size() * taps];
        for (var row = 0; row < kept.size(); row++) {
            var phase = kept.get(row);
            var values = phaseCoefficients.get(phase);
            var indexes = phaseIndexes.get(phase);
            for (var tap = 0; tap < values.size(); tap++) {
                coefficients[row * taps + tap] = values.get(tap).floatValue();
                offsets[row * taps + tap] = history - indexes.get(tap);
            }
            for (var tap = values.size(); tap < taps; tap++) {
                offsets[row * taps + tap] = history;
            }
        }
        return new Filter(coefficients, offsets, kept.size(), taps, history);
    }

    /**
     * Measures one block of one channel.
     *
     * @param channel the channel index
     * @param samples the channel's samples
     * @param offset the index of the first sample of the block
     * @param count the number of samples
     * @return the largest magnitude of the oversampled block, not counting the input samples
     * themselves
     */
    float process(int channel, float[] samples, int offset, int count) {
        if (filter == null || count == 0) {
            return 0f;
        }
        var history = histories[channel];
        var length = history.length;
        var peak = 0f;
        var done = 0;
        while (done < count) {
            var chunk = Math.min(CHUNK, count - done);
            System.arraycopy(history, 0, work, 0, length);
            System.arraycopy(samples, offset + done, work, length, chunk);
            peak = Math.max(peak, kernel.peak(work, chunk, filter.coefficients(), filter.offsets(), filter.phases(), filter.taps()));
            System.arraycopy(work, chunk, history, 0, length);
            done += chunk;
        }
        return peak;
    }

    /**
     * Forgets the history of every channel.
     */
    void reset() {
        for (var history : histories) {
            Arrays.fill(history, 0f);
        }
    }
}
