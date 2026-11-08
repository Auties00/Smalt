package com.github.auties00.smalt.audio.process;

import java.util.Arrays;
import java.util.Objects;

/**
 * Changes the tempo of planar audio without changing its pitch, its pitch without changing its tempo, or both.
 *
 * <p>The tempo is time-scaled by waveform-similarity overlap-add (WSOLA): frames of {@value #FRAME_MILLIS} ms with a
 * periodic Hann window are overlapped by half, each taken from near its nominal input position at the offset (within
 * {@value #SEARCH_MILLIS} ms) whose waveform best matches the continuation of the previous frame, measured on the sum
 * of the channels so that every channel moves by the same offset and the stereo image stays intact. A pitch change
 * time-scales by the ratio of tempo to pitch, then resamples by the pitch with a {@link Resampler}. A tempo of
 * {@code t} and a pitch of {@code p} turn {@code n} input frames into exactly {@code round(n / t)} output frames,
 * shorter by a factor {@code t} and with every frequency multiplied by {@code p}; with both at 1 the output equals the
 * input.
 *
 * <p>The caller {@linkplain #write writes} input, {@linkplain #read reads} the output as it becomes available, and
 * calls {@link #finish()} at the end of the stream to release the rest. Memory is bounded by a few frames plus the
 * output not read yet. The output is deterministic: it does not depend on how the input is split into blocks.
 *
 * <p>Instances are not thread-safe.
 */
public final class TimeStretcher {
    /**
     * The length of one overlap-add frame in milliseconds.
     */
    static final int FRAME_MILLIS = 40;

    /**
     * How far from its nominal position, in milliseconds, a frame may be taken.
     */
    static final int SEARCH_MILLIS = 12;

    /**
     * The smallest tempo and pitch accepted.
     */
    public static final double MIN_FACTOR = 0.1;

    /**
     * The largest tempo and pitch accepted.
     */
    public static final double MAX_FACTOR = 10;

    private final int channels;
    private final int sampleRate;
    private final double tempo;
    private final double pitch;

    /**
     * The time-scaling factor of the overlap-add, the tempo over the pitch, or {@code 1} without time-scaling.
     */
    private final double stretch;

    /**
     * The frame length, even.
     */
    private final int frame;

    /**
     * The synthesis hop, half the frame.
     */
    private final int hop;

    /**
     * The search radius in samples.
     */
    private final int search;

    private final double[] window;

    /**
     * The buffered input, one plane per channel, starting at stream index {@link #base}; the stream is the input
     * preceded by {@link #hop} zeros.
     */
    private float[][] input;

    /**
     * The sum of the channels of {@link #input}.
     */
    private float[] mono;

    private int length;

    private long base;

    /**
     * The overlap-add accumulator of the frame being completed.
     */
    private final double[][] overlap;

    /**
     * The index of the next overlap-add frame.
     */
    private long frameIndex;

    /**
     * The stream index the previous frame was taken from.
     */
    private long previous;

    /**
     * The overlap-add samples still to drop: the first hop comes from the leading zeros.
     */
    private int skip;

    /**
     * The overlap-add samples emitted after {@link #skip}.
     */
    private long stretched;

    /**
     * Scratch planes of one hop handed to the resampler or the output.
     */
    private final float[][] hopBlock;

    /**
     * Scratch planes of the resampler's output, or {@code null} without a pitch change.
     */
    private float[][] resampled;

    /**
     * Scratch planes holding input of the resampler that does not start at index zero, or {@code null}.
     */
    private float[][] staging;

    /**
     * The resampler of a pitch change, or {@code null}.
     */
    private final Resampler resampler;

    private final AudioFifo output;

    /**
     * The number of input frames written.
     */
    private long written;

    /**
     * The number of output frames read.
     */
    private long delivered;

    /**
     * The total number of output frames, known once {@link #finish()} ran, or {@code -1}.
     */
    private long total = -1;

    /**
     * Constructs a stretcher.
     *
     * @param channels the number of channels, positive
     * @param sampleRate the sampling rate in hertz, positive
     * @param tempo the tempo factor, from {@value #MIN_FACTOR} to {@value #MAX_FACTOR}; {@code 2} plays twice as fast
     * @param pitch the pitch factor, from {@value #MIN_FACTOR} to {@value #MAX_FACTOR}; {@code 2} raises the pitch by
     * an octave
     * @throws IllegalArgumentException if a count or the rate is not positive, or a factor is out of range
     */
    public TimeStretcher(int channels, int sampleRate, double tempo, double pitch) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channel count " + channels + " is not positive");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampling rate " + sampleRate + " is not positive");
        }
        checkFactor("tempo", tempo);
        checkFactor("pitch", pitch);
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.tempo = tempo;
        this.pitch = pitch;
        this.stretch = tempo / pitch;
        this.frame = Math.max(16, (int) Math.round(sampleRate * FRAME_MILLIS / 1000.0) & ~1);
        this.hop = frame / 2;
        this.search = Math.max(1, (int) Math.round(sampleRate * SEARCH_MILLIS / 1000.0));
        this.window = new double[frame];
        for (var index = 0; index < frame; index++) {
            window[index] = 0.5 - 0.5 * StrictMath.cos(2 * StrictMath.PI * index / frame);
        }
        this.input = new float[channels][4 * frame];
        this.mono = new float[4 * frame];
        this.length = hop;
        this.overlap = new double[channels][frame];
        this.skip = hop;
        this.hopBlock = new float[channels][hop];
        if (pitch != 1) {
            var outputRate = (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(sampleRate / pitch)));
            this.resampler = new Resampler(sampleRate, outputRate, channels, Resampler.Quality.STANDARD);
            var exact = 1 / pitch;
            if (Math.abs(resampler.ratio() - exact) > 1e-12) {
                resampler.setRatio(exact);
            }
        } else {
            this.resampler = null;
        }
        this.output = new AudioFifo(channels);
    }

    /**
     * Rejects a factor out of range.
     *
     * @param name the name of the factor, for the message
     * @param factor the value checked
     * @throws IllegalArgumentException if the factor is out of range
     */
    private static void checkFactor(String name, double factor) {
        if (!(factor >= MIN_FACTOR && factor <= MAX_FACTOR)) {
            throw new IllegalArgumentException(name + " " + factor + " is outside " + MIN_FACTOR + " to " + MAX_FACTOR);
        }
    }

    /**
     * {@return the number of channels}
     */
    public int channels() {
        return channels;
    }

    /**
     * {@return the sampling rate in hertz, the same for the input and the output}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the tempo factor}
     */
    public double tempo() {
        return tempo;
    }

    /**
     * {@return the pitch factor}
     */
    public double pitch() {
        return pitch;
    }

    /**
     * Returns the number of output frames a number of input frames gives.
     *
     * @param inputFrames the number of input frames, zero or more
     * @return {@code round(inputFrames / tempo)}
     */
    public long outputFrames(long inputFrames) {
        return Math.round(inputFrames / tempo);
    }

    /**
     * Adds input.
     *
     * @param planes the samples, one plane per channel
     * @param offset the index in each plane of the first sample
     * @param frames the number of samples per channel
     * @throws IllegalArgumentException if the plane count differs from {@link #channels()}
     * @throws IllegalStateException if {@link #finish()} ran
     * @throws IndexOutOfBoundsException if a plane is shorter than {@code offset + frames}
     */
    public void write(float[][] planes, int offset, int frames) {
        Objects.requireNonNull(planes, "planes");
        if (planes.length != channels) {
            throw new IllegalArgumentException("expected " + channels + " planes, got " + planes.length);
        }
        if (total >= 0) {
            throw new IllegalStateException("the stretcher is finished");
        }
        for (var plane : planes) {
            Objects.checkFromIndexSize(offset, frames, plane.length);
        }
        if (frames == 0) {
            return;
        }
        written += frames;
        if (stretch == 1) {
            emit(planes, offset, frames);
            return;
        }
        ensureCapacity(length + frames);
        for (var channel = 0; channel < channels; channel++) {
            System.arraycopy(planes[channel], offset, input[channel], length, frames);
        }
        for (var index = 0; index < frames; index++) {
            var sum = 0f;
            for (var channel = 0; channel < channels; channel++) {
                sum += planes[channel][offset + index];
            }
            mono[length + index] = sum;
        }
        length += frames;
        run(false);
    }

    /**
     * Ends the input, releasing every remaining output frame.
     *
     * <p>Further calls have no effect.
     */
    public void finish() {
        if (total >= 0) {
            return;
        }
        total = outputFrames(written);
        if (stretch != 1) {
            run(true);
        }
        if (resampler != null) {
            var bound = resampler.maxOutputFrames(0);
            ensureResampled(bound);
            var frames = resampler.flush(resampled, 0);
            output.write(resampled, 0, frames);
        }
        var missing = total - delivered - output.size();
        if (missing > 0) {
            var zeros = new float[channels][(int) Math.min(missing, hop)];
            while (missing > 0) {
                var count = (int) Math.min(missing, zeros[0].length);
                output.write(zeros, 0, count);
                missing -= count;
            }
        }
    }

    /**
     * Returns the number of output frames {@link #read(float[][], int, int)} can return now.
     *
     * @return the frame count
     */
    public int available() {
        var limit = (total >= 0 ? total : (long) Math.floor(written / tempo)) - delivered;
        return (int) Math.max(0, Math.min(output.size(), limit));
    }

    /**
     * Reads output frames.
     *
     * @param planes receives the samples, one plane per channel
     * @param offset the index in each plane of the first sample written
     * @param frames the largest number of samples per channel to read
     * @return the number of frames read, {@code 0} when none is available yet or the output ended
     * @throws IllegalArgumentException if the plane count differs from {@link #channels()}
     * @throws IndexOutOfBoundsException if a plane is shorter than {@code offset + frames}
     */
    public int read(float[][] planes, int offset, int frames) {
        Objects.requireNonNull(planes, "planes");
        if (planes.length != channels) {
            throw new IllegalArgumentException("expected " + channels + " planes, got " + planes.length);
        }
        var count = output.read(planes, offset, Math.min(frames, available()));
        delivered += count;
        return count;
    }

    /**
     * Runs the overlap-add over the buffered input.
     *
     * @param flushing whether the input ended, so that missing input reads as silence
     */
    private void run(boolean flushing) {
        var target = flushing ? Math.round(written / stretch) : Long.MAX_VALUE;
        while (!flushing || stretched < target) {
            var nominal = Math.round(frameIndex * hop * stretch);
            var low = frameIndex == 0 ? 0 : Math.max(0, nominal - search);
            var high = frameIndex == 0 ? 0 : nominal + search;
            var needed = Math.max(high + frame, frameIndex == 0 ? 0 : previous + hop + frame);
            if (base + length < needed) {
                if (!flushing) {
                    return;
                }
                var missing = (int) (needed - base - length);
                ensureCapacity(length + missing);
                for (var channel = 0; channel < channels; channel++) {
                    Arrays.fill(input[channel], length, length + missing, 0f);
                }
                Arrays.fill(mono, length, length + missing, 0f);
                length += missing;
            }
            var start = frameIndex == 0 ? 0 : best(low, high);
            overlapAdd(start);
            previous = start;
            frameIndex++;
            var next = Math.round(frameIndex * hop * stretch);
            discardBefore(Math.min(Math.max(0, next - search), previous + hop));
        }
    }

    /**
     * Finds the frame start in a range whose waveform best matches the continuation of the previous frame.
     *
     * @param low the first candidate stream index
     * @param high the last candidate stream index
     * @return the chosen stream index
     */
    private long best(long low, long high) {
        var natural = (int) (previous + hop - base);
        var first = (int) (low - base);
        var last = (int) (high - base);
        var bestIndex = first;
        var bestScore = Double.NEGATIVE_INFINITY;
        var energy = 0.0;
        for (var n = 0; n < frame; n += 2) {
            energy += (double) mono[first + n] * mono[first + n];
        }
        for (var candidate = first; candidate <= last; candidate += 2) {
            if (candidate > first) {
                double leaving = mono[candidate - 2];
                double entering = mono[candidate - 2 + frame];
                energy = Math.max(0, energy - leaving * leaving + entering * entering);
            }
            var score = correlation(natural, candidate, 2) / Math.sqrt(energy + 1e-9);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = candidate;
            }
        }
        var refined = bestIndex;
        var refinedScore = correlation(natural, bestIndex, 1) / Math.sqrt(energy(bestIndex) + 1e-9);
        for (var candidate = bestIndex - 1; candidate <= bestIndex + 1; candidate += 2) {
            if (candidate < first || candidate > last) {
                continue;
            }
            var score = correlation(natural, candidate, 1) / Math.sqrt(energy(candidate) + 1e-9);
            if (score > refinedScore) {
                refinedScore = score;
                refined = candidate;
            }
        }
        return base + refined;
    }

    /**
     * {@return the correlation of the frames at two buffer indices of {@link #mono}}
     *
     * @param a the first frame's buffer index
     * @param b the second frame's buffer index
     * @param step the sample step, 1 or 2
     */
    private double correlation(int a, int b, int step) {
        var sum = 0.0;
        for (var n = 0; n < frame; n += step) {
            sum += (double) mono[a + n] * mono[b + n];
        }
        return sum;
    }

    /**
     * {@return the energy of the frame at a buffer index of {@link #mono}}
     *
     * @param a the buffer index
     */
    private double energy(int a) {
        var sum = 0.0;
        for (var n = 0; n < frame; n++) {
            sum += (double) mono[a + n] * mono[a + n];
        }
        return sum;
    }

    /**
     * Adds the windowed frame at a stream index to the accumulator and emits the hop it completes.
     *
     * @param start the stream index of the frame
     */
    private void overlapAdd(long start) {
        var at = (int) (start - base);
        for (var channel = 0; channel < channels; channel++) {
            var sums = overlap[channel];
            var plane = input[channel];
            for (var n = 0; n < frame; n++) {
                sums[n] += window[n] * plane[at + n];
            }
            for (var n = 0; n < hop; n++) {
                hopBlock[channel][n] = (float) sums[n];
            }
            System.arraycopy(sums, hop, sums, 0, frame - hop);
            Arrays.fill(sums, frame - hop, frame, 0);
        }
        var from = Math.min(skip, hop);
        skip -= from;
        if (from < hop) {
            stretched += hop - from;
            emit(hopBlock, from, hop - from);
        }
    }

    /**
     * Hands time-scaled frames to the resampler or the output.
     *
     * @param planes the frames
     * @param offset the index of the first frame
     * @param frames the number of frames
     */
    private void emit(float[][] planes, int offset, int frames) {
        if (resampler == null) {
            output.write(planes, offset, frames);
            return;
        }
        var source = planes;
        if (offset != 0) {
            if (staging == null || staging[0].length < frames) {
                staging = new float[channels][Math.max(frames, hop)];
            }
            for (var channel = 0; channel < channels; channel++) {
                System.arraycopy(planes[channel], offset, staging[channel], 0, frames);
            }
            source = staging;
        }
        ensureResampled(resampler.maxOutputFrames(frames));
        var produced = resampler.process(source, frames, resampled, 0);
        output.write(resampled, 0, produced);
    }

    /**
     * Grows the resampler's output planes.
     *
     * @param frames the frames they must hold
     */
    private void ensureResampled(int frames) {
        if (resampled == null || resampled[0].length < frames) {
            resampled = new float[channels][Math.max(frames, hop)];
        }
    }

    /**
     * Grows the input buffer, keeping its contents.
     *
     * @param frames the frames it must hold
     */
    private void ensureCapacity(int frames) {
        if (frames <= mono.length) {
            return;
        }
        var capacity = Math.max(frames, 2 * mono.length);
        for (var channel = 0; channel < channels; channel++) {
            input[channel] = Arrays.copyOf(input[channel], capacity);
        }
        mono = Arrays.copyOf(mono, capacity);
    }

    /**
     * Drops the buffered input before a stream index.
     *
     * @param index the first stream index still needed
     */
    private void discardBefore(long index) {
        var drop = (int) Math.min(length, Math.max(0, index - base));
        if (drop == 0) {
            return;
        }
        for (var channel = 0; channel < channels; channel++) {
            System.arraycopy(input[channel], drop, input[channel], 0, length - drop);
        }
        System.arraycopy(mono, drop, mono, 0, length - drop);
        length -= drop;
        base += drop;
    }
}
