package com.github.auties00.smalt.internal.codec;

import java.util.Arrays;

/**
 * Conceals lost spans of a decoded time-domain signal by repeating its last pitch periods, the packet loss
 * concealment of ITU-T G.711 Appendix I generalized to any sampling rate and channel count, without its output
 * delay.
 *
 * <p>The decoder passes every block it outputs through {@link #recover} and then hands it to {@link #record}, and
 * calls {@link #synthesize} for a lost span. A lost span repeats the last pitch period
 * of the history, found once per loss by normalized cross-correlation over a 5 to 15 ms lag range and shared by
 * every channel; from 10 ms into the loss it repeats the last two periods and from 20 ms the last three, each
 * switch overlapped over a quarter period; the gain falls linearly by 20% per 10 ms from 10 ms on and reaches
 * silence at 60 ms. The blocks after a loss are overlapped with the continued synthesis over 4 ms, plus 4 ms for
 * every 10 ms the loss lasted beyond the first 10 ms, at most 10 ms. Instances are not thread-safe.
 *
 * @implNote This implementation starts the repetition with the period that ends the history and smooths the joint
 * of the repeated periods with a quarter-period overlap inside the copy, since the history was already output;
 * Appendix I instead delays its output by a quarter of the longest period to overlap the end of the history.
 */
public final class WaveformConcealer {
    /**
     * The duration over which the gain stays at 1 and one period repeats, in milliseconds.
     */
    private static final int HOLD_MILLIS = 10;

    /**
     * The duration after which the output is silent, in milliseconds.
     */
    private static final int MUTE_MILLIS = 60;

    /**
     * The overlap of the first block after a loss of at most {@link #HOLD_MILLIS}, in milliseconds.
     */
    private static final int RECOVERY_MILLIS = 4;

    /**
     * The longest overlap of the first block after a loss, in milliseconds.
     */
    private static final int MAX_RECOVERY_MILLIS = 10;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * The shortest pitch period searched, in samples.
     */
    private final int pitchMin;

    /**
     * The longest pitch period searched, in samples.
     */
    private final int pitchMax;

    /**
     * The length of the correlation window of the pitch search, in samples.
     */
    private final int window;

    /**
     * The decimation of the coarse pitch search.
     */
    private final int step;

    /**
     * The number of samples of {@link #HOLD_MILLIS}.
     */
    private final int hold;

    /**
     * The number of samples of {@link #MUTE_MILLIS}.
     */
    private final int mute;

    /**
     * The number of samples of {@link #RECOVERY_MILLIS}.
     */
    private final int recoveryStep;

    /**
     * The number of samples of {@link #MAX_RECOVERY_MILLIS}.
     */
    private final int maxRecovery;

    /**
     * The last samples of each channel, oldest first, the newest at index {@code historyLength - 1}.
     */
    private final float[][] history;

    /**
     * The number of valid samples at the end of each {@link #history} row.
     */
    private int filled;

    /**
     * The repeated periods of each channel: the last three periods of the history and the quarter period before
     * them.
     */
    private final float[][] periods;

    /**
     * The mono mix the pitch search runs on.
     */
    private final float[] mix;

    /**
     * The continued synthesis of each channel overlapped with the first block after a loss.
     */
    private final float[][] tail;

    /**
     * Whether a loss is being concealed and no block followed it yet.
     */
    private boolean lost;

    /**
     * The pitch period of the last loss, in samples.
     */
    private int pitch;

    /**
     * The number of samples of the blocks after the last loss overlapped with the continued synthesis.
     */
    private int overlapLength;

    /**
     * The number of samples of {@link #overlapLength} already overlapped.
     */
    private int overlapped;

    /**
     * The number of periods repeated, from 1 to 3.
     */
    private int cycles;

    /**
     * The position in the repeated span of {@link #periods}.
     */
    private int position;

    /**
     * The number of samples synthesized since the loss began.
     */
    private int elapsed;

    /**
     * The number of samples left in the overlap that follows a change of {@link #cycles}.
     */
    private int fade;

    /**
     * Constructs a concealer.
     *
     * @param channels the number of channels, positive
     * @param sampleRate the sampling rate in hertz, positive
     */
    public WaveformConcealer(int channels, int sampleRate) {
        this.channels = channels;
        this.pitchMin = Math.max(2, sampleRate / 200);
        this.pitchMax = Math.max(pitchMin + 1, 3 * sampleRate / 200);
        this.window = Math.max(4, sampleRate / 50);
        this.step = Math.max(1, sampleRate / 8000);
        this.hold = Math.max(1, sampleRate * HOLD_MILLIS / 1000);
        this.mute = Math.max(hold + 1, sampleRate * MUTE_MILLIS / 1000);
        this.recoveryStep = Math.max(1, sampleRate * RECOVERY_MILLIS / 1000);
        this.maxRecovery = Math.max(recoveryStep, sampleRate * MAX_RECOVERY_MILLIS / 1000);
        var length = Math.max(3 * pitchMax + pitchMax / 4 + 1, window + pitchMax);
        this.history = new float[channels][length];
        this.periods = new float[channels][3 * pitchMax + pitchMax / 4 + 1];
        this.mix = new float[length];
        this.tail = new float[channels][maxRecovery];
    }

    /**
     * Returns an upper bound of the bytes the buffers of a concealer take, for checking against
     * {@link com.github.auties00.smalt.MediaLimits#maxBytes()} before constructing one: the buffers grow with the
     * sampling rate, which a hostile stream may declare as large as its field allows.
     *
     * @param channels the number of channels, positive
     * @param sampleRate the sampling rate in hertz, positive
     * @return the size of the buffers in bytes
     */
    public static long footprint(int channels, int sampleRate) {
        var pitchMax = Math.max(Math.max(2, sampleRate / 200) + 1, 3L * sampleRate / 200);
        var period = 3 * pitchMax + pitchMax / 4 + 1;
        var length = Math.max(period, Math.max(4, sampleRate / 50) + pitchMax);
        var maxRecovery = Math.max(Math.max(1, (long) sampleRate * RECOVERY_MILLIS / 1000),
                (long) sampleRate * MAX_RECOVERY_MILLIS / 1000);
        return (long) Float.BYTES * ((long) channels * (length + period + maxRecovery) + length);
    }

    /**
     * Appends a block the decoder outputs to the history.
     *
     * @param planes the channels of the block
     * @param frames the number of samples per channel
     */
    public void record(float[][] planes, int frames) {
        record(planes, 0, frames);
    }

    /**
     * Appends part of a block the decoder outputs to the history.
     *
     * @param planes the channels of the block
     * @param offset the index of the first sample
     * @param frames the number of samples per channel
     */
    public void record(float[][] planes, int offset, int frames) {
        if (frames <= 0) {
            return;
        }
        var length = history[0].length;
        for (var c = 0; c < channels; c++) {
            var row = history[c];
            var plane = planes[c];
            if (frames >= length) {
                System.arraycopy(plane, offset + frames - length, row, 0, length);
            } else {
                System.arraycopy(row, frames, row, 0, length - frames);
                System.arraycopy(plane, offset, row, length - frames, frames);
            }
        }
        filled = Math.min(length, filled + frames);
    }

    /**
     * Writes the samples of a lost span.
     *
     * @param planes the channels to write
     * @param offset the index of the first sample written
     * @param frames the number of samples per channel
     */
    public void synthesize(float[][] planes, int offset, int frames) {
        if (!lost) {
            begin();
        }
        generate(planes, offset, frames);
        record(planes, offset, frames);
    }

    /**
     * Overlaps a decoded block with the continued synthesis while the blocks after a loss are being overlapped;
     * does nothing otherwise.
     *
     * <p>The decoder calls it on every block before {@link #record}; the overlap may span several short blocks.
     *
     * @param planes the channels of the block, rewritten in place
     * @param frames the number of samples per channel
     */
    public void recover(float[][] planes, int frames) {
        if (lost) {
            lost = false;
            var extra = Math.max(0, elapsed - hold) / hold;
            overlapLength = Math.min(maxRecovery, recoveryStep * (1 + extra));
            overlapped = 0;
        }
        var length = Math.min(overlapLength - overlapped, frames);
        if (length <= 0) {
            return;
        }
        generate(tail, 0, length);
        for (var c = 0; c < channels; c++) {
            var plane = planes[c];
            var synthetic = tail[c];
            for (var i = 0; i < length; i++) {
                var weight = (overlapped + i + 1) / (float) (overlapLength + 1);
                plane[i] = weight * plane[i] + (1 - weight) * synthetic[i];
            }
        }
        overlapped += length;
    }

    /**
     * Starts concealing a loss: finds the pitch period and copies the periods to repeat.
     */
    private void begin() {
        lost = true;
        overlapped = overlapLength = 0;
        pitch = findPitch();
        cycles = 1;
        position = 0;
        elapsed = 0;
        fade = 0;
        var length = history[0].length;
        var copied = 3 * pitch + quarter();
        for (var c = 0; c < channels; c++) {
            System.arraycopy(history[c], length - copied, periods[c], 0, copied);
        }
    }

    /**
     * {@return the length of the quarter-period overlaps, at least one sample}
     */
    private int quarter() {
        return Math.max(1, pitch / 4);
    }

    /**
     * Writes the continued synthesis, advancing the position, the number of repeated periods and the gain.
     *
     * @param planes the channels to write
     * @param offset the index of the first sample written
     * @param frames the number of samples per channel
     */
    private void generate(float[][] planes, int offset, int frames) {
        var overlap = quarter();
        for (var i = 0; i < frames; i++) {
            var t = elapsed + i;
            var gain = t < hold ? 1 : t >= mute ? 0 : 1 - (t - hold) / (float) (mute - hold);
            var wanted = t < hold ? 1 : t < 2 * hold ? 2 : 3;
            if (wanted != cycles && position % pitch == 0) {
                cycles = wanted;
                fade = overlap;
            }
            for (var c = 0; c < channels; c++) {
                planes[c][offset + i] = gain == 0 ? 0 : gain * sample(periods[c], overlap);
            }
            if (fade > 0) {
                fade--;
            }
            position = (position + 1) % (cycles * pitch);
        }
        elapsed += frames;
    }

    /**
     * Returns the sample of the repeated periods at the current position.
     *
     * <p>The repeated span is the last {@link #cycles} periods of the copy. Its last quarter period is overlapped
     * with the quarter period before its start, so the span joins itself smoothly, and the first quarter period
     * after a change of {@link #cycles} is overlapped with the span before the change, a period later in the copy.
     *
     * @param copy the copied history of the channel
     * @param overlap the length of the quarter-period overlaps
     * @return the sample
     */
    private float sample(float[] copy, int overlap) {
        var repeated = cycles * pitch;
        var start = overlap + 3 * pitch - repeated;
        var value = wrapped(copy, start, repeated, position, overlap);
        if (fade > 0) {
            var shorter = repeated - pitch;
            var previous = wrapped(copy, start + pitch, shorter, position % shorter, overlap);
            var weight = (overlap - fade + 1) / (float) (overlap + 1);
            value = weight * value + (1 - weight) * previous;
        }
        return value;
    }

    /**
     * Returns a sample of a repeated span whose last quarter period is overlapped with the samples before it.
     *
     * @param copy the copied history of the channel
     * @param start the index of the span in {@code copy}
     * @param length the length of the span
     * @param at the position in the span
     * @param overlap the length of the overlap
     * @return the sample
     */
    private static float wrapped(float[] copy, int start, int length, int at, int overlap) {
        var value = copy[start + at];
        var into = at - (length - overlap);
        if (into >= 0) {
            var weight = (into + 1) / (float) (overlap + 1);
            value = (1 - weight) * value + weight * copy[start - overlap + into];
        }
        return value;
    }

    /**
     * Returns the pitch period of the end of the history: the lag of the largest normalized cross-correlation
     * between the last {@link #window} samples and the samples that lag behind them, searched every
     * {@link #step} lags on every {@link #step}-th sample and then refined around the best one.
     *
     * @return the period in samples, from {@link #pitchMin} to {@link #pitchMax}
     */
    private int findPitch() {
        var length = history[0].length;
        Arrays.fill(mix, 0);
        for (var c = 0; c < channels; c++) {
            var row = history[c];
            for (var i = 0; i < length; i++) {
                mix[i] += row[i];
            }
        }
        if (filled < window + pitchMax) {
            return pitchMax;
        }
        var best = coarse();
        var from = Math.max(pitchMin, best - step);
        var to = Math.min(pitchMax, best + step);
        var bestScore = Double.NEGATIVE_INFINITY;
        var result = best;
        for (var lag = from; lag <= to; lag++) {
            var score = correlation(lag, 1);
            if (score > bestScore) {
                bestScore = score;
                result = lag;
            }
        }
        return result;
    }

    /**
     * Returns the lag of the largest normalized cross-correlation on the decimated grid.
     *
     * @return the lag in samples
     */
    private int coarse() {
        var bestScore = Double.NEGATIVE_INFINITY;
        var best = pitchMax;
        for (var lag = pitchMin; lag <= pitchMax; lag += step) {
            var score = correlation(lag, step);
            if (score > bestScore) {
                bestScore = score;
                best = lag;
            }
        }
        return best;
    }

    /**
     * Returns the cross-correlation of the last {@link #window} samples of the mix with the samples a lag behind,
     * normalized by the energy of the lagged samples.
     *
     * @param lag the lag in samples
     * @param stride the sample stride
     * @return the normalized correlation, 0 when the lagged samples are silent
     */
    private double correlation(int lag, int stride) {
        var end = mix.length;
        var start = end - window;
        var cross = 0.0;
        var energy = 0.0;
        for (var i = start; i < end; i += stride) {
            var lagged = mix[i - lag];
            cross += mix[i] * lagged;
            energy += lagged * lagged;
        }
        return energy == 0 ? 0 : cross / Math.sqrt(energy);
    }
}
