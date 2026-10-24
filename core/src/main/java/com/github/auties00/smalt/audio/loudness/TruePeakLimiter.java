package com.github.auties00.smalt.audio.loudness;

import java.util.Arrays;
import java.util.Objects;

/**
 * Keeps the true peak of planar float audio at or below a ceiling with a look-ahead limiter.
 *
 * <p>The true peak is detected as {@link LoudnessMeter} measures it (ITU-R BS.1770-4 Annex 2: the
 * input oversampled four times below 96 kHz, twice below 192 kHz, and taken as is from 192 kHz). One
 * gain, shared by every channel so that the stereo image does not move, scales the input: wherever a
 * true peak would exceed the ceiling, every sample that contributes to it is scaled by at most
 * {@code ceiling / peak}, the gain falling towards that value smoothly over the look-ahead before the
 * peak and recovering exponentially with the release time after it. A signal whose true peak stays
 * 0.02 dB or more under the ceiling passes through unchanged, sample for sample.
 *
 * <p>The limiter has <em>zero net delay</em>: output sample {@code n} is input sample {@code n} times
 * its gain. Because the gain of a sample depends on {@link #delay()} later samples,
 * {@link #process(float[][], int, int, float[][], int)} withholds that many frames, which
 * {@link #flush(float[][], int)} releases at the end of the stream; the stream then yields as many
 * frames as it received. The output does not depend on how the input is split into calls.
 *
 * <p>Instances are not thread-safe. After {@link #flush(float[][], int)} a limiter is back in its
 * initial state and can process a new stream.
 *
 * @implNote This implementation holds each oversampled peak's gain over the input samples under the
 * interpolation filter, then takes the minimum over the look-ahead and averages it over the same length;
 * the average of minima over windows that all contain a sample never exceeds that sample's gain. It
 * aims 0.02 dB under the ceiling, which covers the small overshoot a gain that changes under the
 * interpolation filter can cause.
 */
public final class TruePeakLimiter {
    /**
     * The largest accepted channel count.
     */
    private static final int MAX_CHANNELS = 1024;

    /**
     * The smallest accepted sampling rate, in hertz.
     */
    private static final int MIN_RATE = 8000;

    /**
     * The largest accepted sampling rate, in hertz.
     */
    private static final int MAX_RATE = 2_822_400;

    /**
     * The number of samples of each channel's interpolation buffer past its history.
     */
    private static final int CHUNK = 4096;

    /**
     * How far under the ceiling the gain aims, in dB.
     */
    private static final double MARGIN_DB = 0.02;

    /**
     * The fixed-point unit of the averaged gains: gains are summed as multiples of {@code 2^-40}.
     */
    private static final double ONE = 0x1p40;

    /**
     * The settings.
     */
    private final TruePeakLimiterOptions options;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * The sampling rate in hertz.
     */
    private final int sampleRate;

    /**
     * The ceiling the gain aims for, linear.
     */
    private final double ceiling;

    /**
     * The per-sample factor by which the gain reduction decays after a peak.
     */
    private final double release;

    /**
     * The oversampling filter, or {@code null} when the rate needs no oversampling.
     */
    private final TruePeakMeter.Filter filter;

    /**
     * The number of past samples the oversampling filter reads: a peak detected at sample {@code b}
     * depends on samples {@code b - hold} to {@code b}.
     */
    private final int hold;

    /**
     * The look-ahead in samples: the length of the minimum and of the average.
     */
    private final int length;

    /**
     * Each channel's interpolation buffer: {@link #hold} samples of history, then new samples.
     */
    private final float[][] recent;

    /**
     * The number of valid samples in each {@link #recent} buffer.
     */
    private int recentFill;

    /**
     * The delayed input of each channel, indexed by sample index masked with {@link #mask}.
     */
    private final float[][] delayed;

    /**
     * The mask of the {@link #delayed} ring buffers.
     */
    private final int mask;

    /**
     * The sample indexes of the hold window's monotonic queue, a ring of {@code hold + 2}.
     */
    private final long[] holdIndexes;

    /**
     * The required gains of the hold window's monotonic queue, increasing from head to tail.
     */
    private final double[] holdValues;

    /**
     * The head and the size of the hold queue.
     */
    private int holdHead;
    private int holdSize;

    /**
     * The sample indexes of the look-ahead minimum's monotonic queue, a ring of {@code length + 1}.
     */
    private final long[] minimumIndexes;

    /**
     * The gains of the look-ahead minimum's monotonic queue, increasing from head to tail.
     */
    private final double[] minimumValues;

    /**
     * The head and the size of the minimum queue.
     */
    private int minimumHead;
    private int minimumSize;

    /**
     * The last {@link #length} minima in fixed point, a ring.
     */
    private final long[] averaged;

    /**
     * The sum of {@link #averaged}.
     */
    private long averageSum;

    /**
     * The gain reduction after release of the last held sample, {@code 1 - gain}.
     */
    private double reduction;

    /**
     * The number of input frames received since the start of the stream.
     */
    private long received;

    /**
     * The smallest gain applied since construction.
     */
    private double smallestGain;

    /**
     * Constructs a limiter.
     *
     * @param channels the number of channels, from 1 to 1024
     * @param sampleRate the sampling rate in hertz, from 8000 to 2822400
     * @param options the ceiling, look-ahead and release
     * @throws IllegalArgumentException if the channel count or the rate is out of range
     */
    public TruePeakLimiter(int channels, int sampleRate, TruePeakLimiterOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        if (channels < 1 || channels > MAX_CHANNELS) {
            throw new IllegalArgumentException("channel count must be between 1 and " + MAX_CHANNELS + ", got "
                    + channels);
        }
        if (sampleRate < MIN_RATE || sampleRate > MAX_RATE) {
            throw new IllegalArgumentException("sampling rate must be between " + MIN_RATE + " and " + MAX_RATE
                    + " Hz, got " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.ceiling = StrictMath.pow(10, (options.ceilingDb() - MARGIN_DB) / 20);
        this.release = StrictMath.exp(-1000.0 / (options.releaseMillis() * sampleRate));
        this.filter = TruePeakMeter.filter(sampleRate);
        this.hold = filter == null ? 0 : filter.history();
        this.length = Math.max(2 * hold + 1, (int) Math.round(options.lookaheadMillis() * sampleRate / 1000));
        this.recent = new float[channels][hold + CHUNK];
        var size = Integer.highestOneBit(hold + length) << 1;
        this.delayed = new float[channels][size];
        this.mask = size - 1;
        this.holdIndexes = new long[hold + 2];
        this.holdValues = new double[hold + 2];
        this.minimumIndexes = new long[length + 1];
        this.minimumValues = new double[length + 1];
        this.averaged = new long[length];
        this.smallestGain = 1;
        reset();
    }

    /**
     * {@return the options this limiter was created with}
     */
    public TruePeakLimiterOptions options() {
        return options;
    }

    /**
     * {@return the number of channels}
     */
    public int channels() {
        return channels;
    }

    /**
     * {@return the sampling rate in hertz}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * Returns the number of frames {@link #process(float[][], int, int, float[][], int)} withholds until
     * later input or {@link #flush(float[][], int)} releases them.
     *
     * <p>This is a latency, not a shift: the output is never delayed relative to the input.
     *
     * @return the look-ahead in frames
     */
    public int delay() {
        return hold + length - 1;
    }

    /**
     * {@return the largest gain reduction applied since the limiter was created, in dB, 0 when the
     * input never exceeded the ceiling}
     */
    public double maxGainReductionDb() {
        return -20 * StrictMath.log10(smallestGain);
    }

    /**
     * Limits a block of input and writes every output frame whose look-ahead is available.
     *
     * <p>A call writes at most {@code frames} frames.
     *
     * @param input the input planes, one per channel
     * @param inputOffset the index in each input plane of the first frame
     * @param frames the number of input frames; non-negative
     * @param output the output planes, one per channel, distinct from the input planes
     * @param outputOffset the index in each output plane of the first frame written
     * @return the number of frames written to each output plane
     * @throws IllegalArgumentException if a plane count differs from {@link #channels()}, an input plane
     * holds fewer than {@code inputOffset + frames} samples, an output plane cannot hold
     * {@code frames} frames at {@code outputOffset}, or an offset or {@code frames} is negative
     */
    public int process(float[][] input, int inputOffset, int frames, float[][] output, int outputOffset) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (input.length != channels || output.length != channels) {
            throw new IllegalArgumentException("expected " + channels + " planes, got " + input.length + " input and "
                    + output.length + " output planes");
        }
        if (frames < 0 || inputOffset < 0 || outputOffset < 0) {
            throw new IllegalArgumentException("negative frame count or offset");
        }
        for (var c = 0; c < channels; c++) {
            if (Objects.requireNonNull(input[c], "input plane").length - (long) inputOffset < frames) {
                throw new IllegalArgumentException("input plane " + c + " holds fewer than " + frames + " frames");
            }
            if (Objects.requireNonNull(output[c], "output plane").length - (long) outputOffset < frames) {
                throw new IllegalArgumentException("output plane " + c + " cannot hold " + frames + " frames");
            }
        }
        var written = 0;
        for (var i = 0; i < frames; i++) {
            if (push(input, inputOffset + i, false, output, outputOffset + written)) {
                written++;
            }
        }
        return written;
    }

    /**
     * Ends the stream: writes every withheld frame and returns the limiter to its initial state.
     *
     * @param output the output planes, one per channel
     * @param outputOffset the index in each output plane of the first frame written
     * @return the number of frames written to each output plane, at most {@link #delay()}
     * @throws IllegalArgumentException if the plane count differs from {@link #channels()} or an output
     * plane cannot hold the frames produced
     */
    public int flush(float[][] output, int outputOffset) {
        Objects.requireNonNull(output, "output");
        if (output.length != channels) {
            throw new IllegalArgumentException("expected " + channels + " output planes, got " + output.length);
        }
        if (outputOffset < 0) {
            throw new IllegalArgumentException("negative offset " + outputOffset);
        }
        var pending = (int) Math.min(received, delay());
        for (var c = 0; c < channels; c++) {
            if (Objects.requireNonNull(output[c], "output plane").length - (long) outputOffset < pending) {
                throw new IllegalArgumentException("output plane " + c + " cannot hold " + pending + " frames");
            }
        }
        var end = received;
        var written = 0;
        while (written < pending) {
            if (push(null, 0, true, output, outputOffset + written) && received - 1 - delay() < end) {
                written++;
            }
        }
        reset();
        return written;
    }

    /**
     * Feeds one frame through the limiter and writes the frame it releases, if any.
     *
     * @param input the input planes, or {@code null} with {@code silence}
     * @param index the index of the frame in {@code input}
     * @param silence whether the frame is silence padding the end of the stream
     * @param output the output planes
     * @param outputIndex the index in each output plane of the frame released
     * @return whether a frame was released
     */
    private boolean push(float[][] input, int index, boolean silence, float[][] output, int outputIndex) {
        if (recentFill == recent[0].length) {
            for (var plane : recent) {
                System.arraycopy(plane, recentFill - hold, plane, 0, hold);
            }
            recentFill = hold;
        }
        var sample = received++;
        var slot = (int) (sample & mask);
        var peak = 0.0;
        for (var c = 0; c < channels; c++) {
            var value = silence ? 0f : input[c][index];
            if (Float.isNaN(value)) {
                value = 0f;
            }
            recent[c][recentFill] = value;
            delayed[c][slot] = value;
            peak = Math.max(peak, Math.abs(value));
            if (filter != null) {
                peak = Math.max(peak, interpolatedPeak(recent[c], recentFill));
            }
        }
        recentFill++;
        pushHold(sample, peak > ceiling ? ceiling / peak : 1);
        var held = sample - hold;
        if (held < 0) {
            return false;
        }
        while (holdIndexes[holdHead] < held) {
            holdHead = (holdHead + 1) % holdIndexes.length;
            holdSize--;
        }
        reduction = Math.max(1 - holdValues[holdHead], reduction * release);
        var minimum = pushMinimum(held, 1 - reduction);
        var ring = (int) (held % length);
        var fixed = (long) Math.floor(minimum * ONE);
        averageSum += fixed - averaged[ring];
        averaged[ring] = fixed;
        var released = held - length + 1;
        if (released < 0) {
            return false;
        }
        var gain = averageSum / (length * ONE);
        smallestGain = Math.min(smallestGain, gain);
        var source = (int) (released & mask);
        for (var c = 0; c < channels; c++) {
            output[c][outputIndex] = (float) (delayed[c][source] * gain);
        }
        return true;
    }

    /**
     * Returns the largest magnitude of the oversampled points that the newest sample of a buffer
     * completes.
     *
     * @param buffer a channel's interpolation buffer
     * @param position the index of the newest sample, at least {@link #hold}
     * @return the magnitude
     */
    private double interpolatedPeak(float[] buffer, int position) {
        var coefficients = filter.coefficients();
        var offsets = filter.offsets();
        var taps = filter.taps();
        var base = position - hold;
        var peak = 0f;
        for (var phase = 0; phase < filter.phases(); phase++) {
            var row = phase * taps;
            var sum = 0f;
            for (var tap = 0; tap < taps; tap++) {
                sum = Math.fma(coefficients[row + tap], buffer[base + offsets[row + tap]], sum);
            }
            peak = Math.max(peak, Math.abs(sum));
        }
        return peak;
    }

    /**
     * Appends a required gain to the hold window's monotonic queue.
     *
     * @param sample the sample index
     * @param gain the gain the sample's peak requires
     */
    private void pushHold(long sample, double gain) {
        var capacity = holdIndexes.length;
        while (holdSize > 0 && holdValues[(holdHead + holdSize - 1) % capacity] >= gain) {
            holdSize--;
        }
        var tail = (holdHead + holdSize) % capacity;
        holdIndexes[tail] = sample;
        holdValues[tail] = gain;
        holdSize++;
    }

    /**
     * Appends a released gain to the look-ahead minimum's monotonic queue and returns the minimum over
     * the last {@link #length} samples.
     *
     * @param sample the sample index
     * @param gain the gain of the sample after release
     * @return the minimum
     */
    private double pushMinimum(long sample, double gain) {
        var capacity = minimumIndexes.length;
        while (minimumSize > 0 && minimumValues[(minimumHead + minimumSize - 1) % capacity] >= gain) {
            minimumSize--;
        }
        var tail = (minimumHead + minimumSize) % capacity;
        minimumIndexes[tail] = sample;
        minimumValues[tail] = gain;
        minimumSize++;
        while (minimumIndexes[minimumHead] <= sample - length) {
            minimumHead = (minimumHead + 1) % capacity;
            minimumSize--;
        }
        return minimumValues[minimumHead];
    }

    /**
     * Returns the limiter to the start of a new stream.
     */
    private void reset() {
        for (var plane : recent) {
            Arrays.fill(plane, 0f);
        }
        recentFill = hold;
        holdHead = 0;
        holdSize = 0;
        minimumHead = 0;
        minimumSize = 0;
        Arrays.fill(averaged, (long) ONE);
        averageSum = (long) ONE * length;
        reduction = 0;
        received = 0;
    }
}
