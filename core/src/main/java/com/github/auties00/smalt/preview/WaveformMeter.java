package com.github.auties00.smalt.preview;

import com.github.auties00.smalt.audio.AudioBuffer;

import java.util.Arrays;
import java.util.Objects;

/**
 * Computes a {@link Waveform} from audio fed one block at a time, in bounded memory.
 *
 * <p>Samples are fed with {@link #accept(float[][], int, int, int)} (planar, one array per channel) or
 * {@link #accept(AudioBuffer)}, in order, and {@link #finish()} returns the waveform. The
 * channels combine as {@link WaveformOptions#channels()} says. In frames-per-bucket mode every
 * bucket is exact and memory grows with the number of buckets. In bucket-count mode the length of
 * the audio is unknown until {@link #finish()}, so the meter keeps running totals of chunks of
 * consecutive sample frames, at most {@value #MAX_CHUNKS} of them: chunks start one frame wide and
 * double in width, by merging neighbors, whenever that many are filled. Bucket {@code k} of
 * {@code n} then covers the frames from {@code ceil(k * total / n)} up to
 * {@code ceil((k + 1) * total / n)} (at least one frame when there is audio); its sums are exact
 * while the audio holds fewer than {@value #MAX_CHUNKS} frames, and beyond that a chunk split by a
 * bucket boundary is shared between the two buckets in proportion to the frames on each side,
 * while its minimum and maximum count for both.
 *
 * <p>An instance is not thread-safe.
 */
public final class WaveformMeter {
    /**
     * The largest number of chunks kept in bucket-count mode.
     */
    private static final int MAX_CHUNKS = 1 << 15;

    private final WaveformOptions options;

    /**
     * The sum of absolute values of each chunk or bucket.
     */
    private double[] sumAbs;

    /**
     * The sum of squared values of each chunk or bucket.
     */
    private double[] sumSquares;

    /**
     * The smallest value of each chunk or bucket.
     */
    private float[] minimum;

    /**
     * The largest value of each chunk or bucket.
     */
    private float[] maximum;

    /**
     * The number of values of each chunk or bucket (frames times channels in
     * {@link WaveformOptions.Channels#ALL} mode).
     */
    private long[] values;

    /**
     * The number of completed chunks or buckets.
     */
    private int count;

    /**
     * The frames per chunk or bucket.
     */
    private long width;

    /**
     * The frames of the chunk or bucket being filled.
     */
    private long partialFrames;

    /**
     * The total frames fed.
     */
    private long frames;

    /**
     * The sampling rate of the fed audio, or 0 when unknown.
     */
    private int sampleRate;

    private boolean finished;

    /**
     * Constructs a meter.
     *
     * @param options the bucket layout and channel mode
     */
    public WaveformMeter(WaveformOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        var capacity = options.buckets().isPresent() ? MAX_CHUNKS + 1 : 64;
        this.sumAbs = new double[capacity];
        this.sumSquares = new double[capacity];
        this.minimum = new float[capacity];
        this.maximum = new float[capacity];
        this.values = new long[capacity];
        this.width = options.framesPerBucket().orElse(1);
        resetSlot(0);
    }

    /**
     * Clears the totals of a chunk or bucket.
     *
     * @param slot the index of the chunk or bucket
     */
    private void resetSlot(int slot) {
        sumAbs[slot] = 0;
        sumSquares[slot] = 0;
        minimum[slot] = Float.POSITIVE_INFINITY;
        maximum[slot] = Float.NEGATIVE_INFINITY;
        values[slot] = 0;
    }

    /**
     * Feeds a block of audio.
     *
     * @param buffer the block; its sampling rate is recorded in the waveform
     * @throws IllegalStateException if {@link #finish()} ran
     */
    public void accept(AudioBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        sampleRate = buffer.sampleRate();
        feed(buffer.planes(), 0, buffer.frames());
    }

    /**
     * Feeds planar samples taken at a sampling rate, which the waveform records as an
     * {@link AudioBuffer} block does.
     *
     * @param planes the channels, one array each, holding samples nominally within -1 to 1
     * @param offset the index of the first sample frame in every plane
     * @param length the number of sample frames
     * @param sampleRate the sampling rate of the samples in hertz, positive
     * @throws IllegalArgumentException if {@code planes} is empty or {@code sampleRate} is not
     * positive
     * @throws IllegalStateException if {@link #finish()} ran
     * @throws IndexOutOfBoundsException if the range exceeds a plane
     */
    public void accept(float[][] planes, int offset, int length, int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sample rate " + sampleRate + " is not positive");
        }
        feed(planes, offset, length);
        this.sampleRate = sampleRate;
    }

    /**
     * Feeds planar samples without recording a sampling rate.
     *
     * @param planes the channels, one array each, holding samples nominally within -1 to 1
     * @param offset the index of the first sample frame in every plane
     * @param length the number of sample frames
     * @throws IllegalArgumentException if {@code planes} is empty
     * @throws IllegalStateException if {@link #finish()} ran
     * @throws IndexOutOfBoundsException if the range exceeds a plane
     */
    private void feed(float[][] planes, int offset, int length) {
        Objects.requireNonNull(planes, "planes");
        if (planes.length == 0) {
            throw new IllegalArgumentException("no channel");
        }
        if (finished) {
            throw new IllegalStateException("the waveform is finished");
        }
        for (var plane : planes) {
            Objects.checkFromIndexSize(offset, length, plane.length);
        }
        var mode = options.channels();
        var index = offset;
        var end = offset + length;
        while (index < end) {
            var take = (int) Math.min(end - index, width - partialFrames);
            var abs = 0.0;
            var squares = 0.0;
            var low = minimum[count];
            var high = maximum[count];
            long weight;
            switch (mode) {
                case FIRST -> {
                    var plane = planes[0];
                    for (var frame = index; frame < index + take; frame++) {
                        var value = plane[frame];
                        abs += Math.abs(value);
                        squares += (double) value * value;
                        low = Math.min(low, value);
                        high = Math.max(high, value);
                    }
                    weight = take;
                }
                case ALL -> {
                    for (var frame = index; frame < index + take; frame++) {
                        for (var plane : planes) {
                            var value = plane[frame];
                            abs += Math.abs(value);
                            squares += (double) value * value;
                            low = Math.min(low, value);
                            high = Math.max(high, value);
                        }
                    }
                    weight = (long) take * planes.length;
                }
                default -> {
                    for (var frame = index; frame < index + take; frame++) {
                        var sum = 0.0f;
                        for (var plane : planes) {
                            sum += plane[frame];
                        }
                        var value = sum / planes.length;
                        abs += Math.abs(value);
                        squares += (double) value * value;
                        low = Math.min(low, value);
                        high = Math.max(high, value);
                    }
                    weight = take;
                }
            }
            sumAbs[count] += abs;
            sumSquares[count] += squares;
            minimum[count] = low;
            maximum[count] = high;
            values[count] += weight;
            partialFrames += take;
            frames += take;
            index += take;
            if (partialFrames == width) {
                completeSlot();
            }
        }
    }

    /**
     * Closes the chunk or bucket being filled and opens the next, merging chunks in bucket-count
     * mode when the store is full.
     */
    private void completeSlot() {
        count++;
        partialFrames = 0;
        if (options.buckets().isPresent()) {
            if (count == MAX_CHUNKS) {
                for (var index = 0; index < count / 2; index++) {
                    var left = 2 * index;
                    sumAbs[index] = sumAbs[left] + sumAbs[left + 1];
                    sumSquares[index] = sumSquares[left] + sumSquares[left + 1];
                    minimum[index] = Math.min(minimum[left], minimum[left + 1]);
                    maximum[index] = Math.max(maximum[left], maximum[left + 1]);
                    values[index] = values[left] + values[left + 1];
                }
                count /= 2;
                width *= 2;
            }
        } else if (count + 1 >= sumAbs.length) {
            var capacity = sumAbs.length * 2;
            sumAbs = Arrays.copyOf(sumAbs, capacity);
            sumSquares = Arrays.copyOf(sumSquares, capacity);
            minimum = Arrays.copyOf(minimum, capacity);
            maximum = Arrays.copyOf(maximum, capacity);
            values = Arrays.copyOf(values, capacity);
        }
        resetSlot(count);
    }

    /**
     * {@return the number of sample frames fed so far}
     */
    public long frames() {
        return frames;
    }

    /**
     * Builds the waveform of everything fed; later calls return an equal waveform.
     *
     * @return the waveform
     */
    public Waveform finish() {
        finished = true;
        if (options.framesPerBucket().isPresent()) {
            var buckets = partialFrames > 0 ? count + 1 : count;
            var low = new float[buckets];
            var high = new float[buckets];
            var rms = new float[buckets];
            var mean = new float[buckets];
            for (var bucket = 0; bucket < buckets; bucket++) {
                var weight = values[bucket];
                low[bucket] = weight == 0 ? 0 : minimum[bucket];
                high[bucket] = weight == 0 ? 0 : maximum[bucket];
                mean[bucket] = weight == 0 ? 0 : (float) (sumAbs[bucket] / weight);
                rms[bucket] = weight == 0 ? 0 : (float) Math.sqrt(sumSquares[bucket] / weight);
            }
            return new Waveform(low, high, rms, mean, frames, sampleRate);
        }
        var buckets = options.buckets().orElseThrow();
        var low = new float[buckets];
        var high = new float[buckets];
        var rms = new float[buckets];
        var mean = new float[buckets];
        if (frames == 0) {
            return new Waveform(low, high, rms, mean, 0, sampleRate);
        }
        var chunks = partialFrames > 0 ? count + 1 : count;
        var prefixAbs = new double[chunks + 1];
        var prefixSquares = new double[chunks + 1];
        var prefixValues = new double[chunks + 1];
        for (var chunk = 0; chunk < chunks; chunk++) {
            prefixAbs[chunk + 1] = prefixAbs[chunk] + sumAbs[chunk];
            prefixSquares[chunk + 1] = prefixSquares[chunk] + sumSquares[chunk];
            prefixValues[chunk + 1] = prefixValues[chunk] + values[chunk];
        }
        for (var bucket = 0; bucket < buckets; bucket++) {
            var start = Math.ceilDiv(bucket * frames, buckets);
            var end = Math.max(Math.ceilDiv((bucket + 1) * frames, buckets), Math.min(start + 1, frames));
            start = Math.min(start, end - 1);
            var weight = cumulative(prefixValues, values, end) - cumulative(prefixValues, values, start);
            var abs = cumulative(prefixAbs, sumAbs, end) - cumulative(prefixAbs, sumAbs, start);
            var squares = cumulative(prefixSquares, sumSquares, end) - cumulative(prefixSquares, sumSquares, start);
            mean[bucket] = weight <= 0 ? 0 : (float) (abs / weight);
            rms[bucket] = weight <= 0 ? 0 : (float) Math.sqrt(Math.max(0, squares / weight));
            var first = (int) Math.min(chunks - 1, start / width);
            var last = (int) Math.min(chunks - 1, (end - 1) / width);
            var lowest = Float.POSITIVE_INFINITY;
            var highest = Float.NEGATIVE_INFINITY;
            for (var chunk = first; chunk <= last; chunk++) {
                lowest = Math.min(lowest, minimum[chunk]);
                highest = Math.max(highest, maximum[chunk]);
            }
            low[bucket] = Float.isInfinite(lowest) ? 0 : lowest;
            high[bucket] = Float.isInfinite(highest) ? 0 : highest;
        }
        return new Waveform(low, high, rms, mean, frames, sampleRate);
    }

    /**
     * Returns a running total over the first frames, interpolating inside the chunk the position
     * falls in.
     *
     * @param prefix the totals of the whole chunks before each index
     * @param chunk the total of each chunk
     * @param frame the number of leading frames
     * @return the total over those frames
     */
    private double cumulative(double[] prefix, double[] chunk, long frame) {
        var whole = (int) Math.min(frame / width, prefix.length - 1);
        var sum = prefix[whole];
        var rest = frame - whole * width;
        if (rest > 0 && whole < prefix.length - 1) {
            var size = whole < count ? width : partialFrames;
            sum += size == 0 ? 0 : chunk[whole] * rest / size;
        }
        return sum;
    }

    /**
     * Returns a running count over the first frames, interpolating inside the chunk the position
     * falls in.
     *
     * @param prefix the counts of the whole chunks before each index
     * @param chunk the count of each chunk
     * @param frame the number of leading frames
     * @return the count over those frames
     */
    private double cumulative(double[] prefix, long[] chunk, long frame) {
        var whole = (int) Math.min(frame / width, prefix.length - 1);
        var sum = prefix[whole];
        var rest = frame - whole * width;
        if (rest > 0 && whole < prefix.length - 1) {
            var size = whole < count ? width : partialFrames;
            sum += size == 0 ? 0 : (double) chunk[whole] * rest / size;
        }
        return sum;
    }
}
