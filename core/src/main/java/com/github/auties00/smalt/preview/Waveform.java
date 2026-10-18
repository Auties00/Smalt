package com.github.auties00.smalt.preview;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Summarizes the amplitude of audio over consecutive buckets of sample frames: per bucket the
 * minimum and maximum sample, the peak, the root mean square and the mean absolute value.
 *
 * <p>Obtained from {@link #measure(MediaSource)} for any audio file or video with audio, or from a
 * {@link WaveformMeter} fed with samples. Values are in the sample scale, nominally -1 to 1;
 * {@link #scaled(Measure, int)} turns one measure into integers relative to the loudest bucket,
 * the form a static waveform display or a compact waveform field stores. Instances are immutable
 * and thread-safe; the array accessors return copies.
 */
public final class Waveform {
    /**
     * The smallest sample of each bucket.
     */
    private final float[] minimums;

    /**
     * The largest sample of each bucket.
     */
    private final float[] maximums;

    /**
     * The root mean square of each bucket.
     */
    private final float[] rms;

    /**
     * The mean absolute value of each bucket.
     */
    private final float[] meanAbsolutes;

    /**
     * The number of sample frames summarized.
     */
    private final long frames;

    /**
     * The sampling rate of the audio, or {@code 0} when unknown.
     */
    private final int sampleRate;

    /**
     * Constructs a waveform.
     *
     * @param minimums      the bucket minimums, not copied
     * @param maximums      the bucket maximums, not copied
     * @param rms           the bucket root mean squares, not copied
     * @param meanAbsolutes the bucket mean absolute values, not copied
     * @param frames        the number of sample frames
     * @param sampleRate    the sampling rate, or {@code 0}
     */
    Waveform(float[] minimums, float[] maximums, float[] rms, float[] meanAbsolutes, long frames, int sampleRate) {
        this.minimums = minimums;
        this.maximums = maximums;
        this.rms = rms;
        this.meanAbsolutes = meanAbsolutes;
        this.frames = frames;
        this.sampleRate = sampleRate;
    }

    /**
     * Measures the waveform of the first audio track of a file with
     * {@link WaveformOptions#defaults()}.
     *
     * @param source the audio file or video, read from offset zero; not closed
     * @return the waveform
     * @throws MediaException.Unsupported if the input has no decodable audio track
     * @throws MediaException.Malformed if the input is corrupt
     * @throws MediaException.LimitExceeded if the input exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     */
    public static Waveform measure(MediaSource source) {
        return measure(source, WaveformOptions.defaults());
    }

    /**
     * Measures the waveform of the first audio track of a file.
     *
     * <p>The track is decoded with the encoder priming and padding dropped, at the sampling rate of
     * the options; in {@link WaveformOptions.Channels#MIX} mode it is mixed down to mono before
     * measuring. Memory stays bounded as {@link WaveformMeter} describes.
     *
     * @param source the audio file or video, read from offset zero; not closed
     * @param options the bucket layout, channel mode, decoding rate and limits
     * @return the waveform
     * @throws MediaException.Unsupported if the input has no decodable audio track
     * @throws MediaException.Malformed if the input is corrupt
     * @throws MediaException.LimitExceeded if the input exceeds the limits of {@code options}
     * @throws MediaException.Io if reading the source fails
     */
    public static Waveform measure(MediaSource source, WaveformOptions options) {
        Objects.requireNonNull(source, "source");
        var readerOptions = AudioReaderOptions.builder().limits(options.limits());
        options.sampleRate().ifPresent(readerOptions::sampleRate);
        if (options.channels() == WaveformOptions.Channels.MIX) {
            readerOptions.layout(ChannelLayout.MONO);
        }
        var meter = new WaveformMeter(options);
        try (var reader = AudioReader.open(source, readerOptions.build())) {
            for (var block = reader.read(); block != null; block = reader.read()) {
                meter.accept(block);
            }
        }
        return meter.finish();
    }

    /**
     * Measures the waveform of the first audio track of a file on disk with
     * {@link WaveformOptions#defaults()}; the file is opened and closed by this method.
     *
     * @param file the audio file or video
     * @return the waveform
     * @throws MediaException.Unsupported if the file has no decodable audio track
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.LimitExceeded if the file exceeds the default limits
     * @throws MediaException.Io if the file cannot be read
     */
    public static Waveform measure(Path file) {
        return measure(file, WaveformOptions.defaults());
    }

    /**
     * Measures the waveform of the first audio track of a file on disk, as
     * {@link #measure(MediaSource, WaveformOptions)} describes; the file is opened and closed by
     * this method.
     *
     * @param file the audio file or video
     * @param options the bucket layout, channel mode, decoding rate and limits
     * @return the waveform
     * @throws MediaException.Unsupported if the file has no decodable audio track
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.LimitExceeded if the file exceeds the limits of {@code options}
     * @throws MediaException.Io if the file cannot be read
     */
    public static Waveform measure(Path file, WaveformOptions options) {
        Objects.requireNonNull(options, "options");
        try (var source = MediaSource.of(file)) {
            return measure(source, options);
        }
    }

    /**
     * Returns the number of buckets.
     *
     * @return the bucket count
     */
    public int bucketCount() {
        return maximums.length;
    }

    /**
     * Returns the number of sample frames summarized.
     *
     * @return the frame count
     */
    public long frames() {
        return frames;
    }

    /**
     * Returns the sampling rate of the summarized audio.
     *
     * @return the rate in hertz, or {@code 0} when the samples were fed without one
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * Returns the smallest sample of a bucket.
     *
     * @param bucket the bucket index
     * @return the minimum, {@code 0} for an empty bucket
     * @throws IndexOutOfBoundsException if {@code bucket} is out of range
     */
    public float min(int bucket) {
        return minimums[bucket];
    }

    /**
     * Returns the largest sample of a bucket.
     *
     * @param bucket the bucket index
     * @return the maximum, {@code 0} for an empty bucket
     * @throws IndexOutOfBoundsException if {@code bucket} is out of range
     */
    public float max(int bucket) {
        return maximums[bucket];
    }

    /**
     * Returns the largest absolute sample of a bucket.
     *
     * @param bucket the bucket index
     * @return the peak, the larger magnitude of the minimum and the maximum
     * @throws IndexOutOfBoundsException if {@code bucket} is out of range
     */
    public float peak(int bucket) {
        return Math.max(Math.abs(minimums[bucket]), Math.abs(maximums[bucket]));
    }

    /**
     * Returns the root mean square of the samples of a bucket.
     *
     * @param bucket the bucket index
     * @return the root mean square, {@code 0} for an empty bucket
     * @throws IndexOutOfBoundsException if {@code bucket} is out of range
     */
    public float rms(int bucket) {
        return rms[bucket];
    }

    /**
     * Returns the mean absolute value of the samples of a bucket.
     *
     * @param bucket the bucket index
     * @return the mean absolute value, {@code 0} for an empty bucket
     * @throws IndexOutOfBoundsException if {@code bucket} is out of range
     */
    public float meanAbsolute(int bucket) {
        return meanAbsolutes[bucket];
    }

    /**
     * Returns the value of a measure for a bucket.
     *
     * @param measure the measure read
     * @param bucket  the bucket index
     * @return the value
     * @throws NullPointerException      if {@code measure} is {@code null}
     * @throws IndexOutOfBoundsException if {@code bucket} is out of range
     */
    public float value(Measure measure, int bucket) {
        return switch (Objects.requireNonNull(measure, "measure")) {
            case PEAK -> peak(bucket);
            case RMS -> rms(bucket);
            case MEAN_ABSOLUTE -> meanAbsolute(bucket);
        };
    }

    /**
     * Returns the minimum of every bucket.
     *
     * @return a copy of the minimums
     */
    public float[] minimums() {
        return minimums.clone();
    }

    /**
     * Returns the maximum of every bucket.
     *
     * @return a copy of the maximums
     */
    public float[] maximums() {
        return maximums.clone();
    }

    /**
     * Returns the values of a measure for every bucket.
     *
     * @param measure the measure read
     * @return a new array of the values
     * @throws NullPointerException if {@code measure} is {@code null}
     */
    public float[] values(Measure measure) {
        var result = new float[bucketCount()];
        for (var bucket = 0; bucket < result.length; bucket++) {
            result[bucket] = value(measure, bucket);
        }
        return result;
    }

    /**
     * Scales a measure relative to the loudest bucket: each bucket's value divided by the largest
     * value of any bucket, times {@code maxValue}, rounded down.
     *
     * <p>Silence, where every value is zero, scales to zeros.
     *
     * @param measure the measure read
     * @param maxValue the value of the loudest bucket, positive
     * @return a new array of values from {@code 0} to {@code maxValue}
     * @throws NullPointerException     if {@code measure} is {@code null}
     * @throws IllegalArgumentException if {@code maxValue} is not positive
     */
    public int[] scaled(Measure measure, int maxValue) {
        if (maxValue <= 0) {
            throw new IllegalArgumentException("maximum value must be positive: " + maxValue);
        }
        var values = values(measure);
        var loudest = 0.0;
        for (var value : values) {
            loudest = Math.max(loudest, value);
        }
        var result = new int[values.length];
        if (loudest <= 0) {
            return result;
        }
        for (var bucket = 0; bucket < values.length; bucket++) {
            result[bucket] = Math.clamp((int) (values[bucket] / loudest * maxValue), 0, maxValue);
        }
        return result;
    }

    @Override
    public String toString() {
        return "Waveform[buckets=" + bucketCount() + ", frames=" + frames + ", sampleRate=" + sampleRate + "]";
    }

    /**
     * Enumerates the per-bucket loudness measures.
     *
     * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
     * {@code default} branch.
     */
    public enum Measure {
        /**
         * The largest absolute sample: what a peak meter or a classic waveform drawing shows.
         */
        PEAK,

        /**
         * The root mean square: the signal's power, closest to perceived loudness.
         */
        RMS,

        /**
         * The mean absolute value.
         */
        MEAN_ABSOLUTE
    }
}
