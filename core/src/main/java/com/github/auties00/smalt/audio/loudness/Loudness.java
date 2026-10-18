package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Measures the loudness of media files, computes the gains that normalize them, and finds their
 * silences.
 *
 * <p>The file's first audio track is decoded at its own rate and layout through
 * {@link AudioReader} and measured by a {@link LoudnessMeter} or scanned by a
 * {@link SilenceDetector}; memory stays bounded whatever the duration. Each method taking a
 * {@link Path} opens the file for the call and closes it before returning.
 *
 * <p>This class is thread-safe.
 */
public final class Loudness {
    /**
     * Prevents instantiation.
     */
    private Loudness() {
        throw new AssertionError();
    }

    /**
     * Measures the first audio track of a file with every measurement on.
     *
     * @param source the input
     * @return the measurements
     * @throws NullPointerException       if {@code source} is {@code null}
     * @throws IllegalArgumentException   if the track's sampling rate is below 8 kHz
     * @throws MediaException.Unsupported if the input has no decodable audio track
     * @throws MediaException.Malformed   if the input is corrupt
     * @throws MediaException.Io          if reading the source fails
     */
    public static LoudnessStats measure(MediaSource source) {
        return measure(source, LoudnessMeterOptions.defaults());
    }

    /**
     * Measures the first audio track of a file.
     *
     * @param source  the input
     * @param options the meter options
     * @return the measurements
     * @throws NullPointerException       if an argument is {@code null}
     * @throws IllegalArgumentException   if the track's sampling rate is below 8 kHz, or the options
     *                                    have explicit weights for another number of channels
     * @throws MediaException.Unsupported if the input has no decodable audio track
     * @throws MediaException.Malformed   if the input is corrupt
     * @throws MediaException.Io          if reading the source fails
     */
    public static LoudnessStats measure(MediaSource source, LoudnessMeterOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var reader = AudioReader.open(source)) {
            var meter = new LoudnessMeter(reader.layout(), reader.sampleRate(), options);
            for (var buffer = reader.read(); buffer != null; buffer = reader.read()) {
                meter.accept(buffer);
            }
            return meter.stats();
        }
    }

    /**
     * Measures the first audio track of a file and returns the gain that normalizes it.
     *
     * @param source        the input
     * @param normalization the target and the true-peak ceiling
     * @return the gain in decibels ({@link LoudnessNormalization#gainDb(LoudnessStats)})
     * @throws NullPointerException       if an argument is {@code null}
     * @throws IllegalArgumentException   if the track's sampling rate is below 8 kHz
     * @throws MediaException.Unsupported if the input has no decodable audio track
     * @throws MediaException.Malformed   if the input is corrupt
     * @throws MediaException.Io          if reading the source fails
     */
    public static double normalizationGainDb(MediaSource source, LoudnessNormalization normalization) {
        Objects.requireNonNull(normalization, "normalization");
        return normalization.gainDb(measure(source));
    }

    /**
     * Measures the first audio track of a file on disk with every measurement on.
     *
     * @param file the media file, opened for the call and closed before it returns
     * @return the measurements
     * @throws IllegalArgumentException if the track's sampling rate is below 8 kHz
     * @throws MediaException.Unsupported if the file has no decodable audio track
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.Io if the file cannot be opened or read
     */
    public static LoudnessStats measure(Path file) {
        return measure(file, LoudnessMeterOptions.defaults());
    }

    /**
     * Measures the first audio track of a file on disk.
     *
     * @param file the media file, opened for the call and closed before it returns
     * @param options the meter options
     * @return the measurements
     * @throws IllegalArgumentException if the track's sampling rate is below 8 kHz, or the options
     * have explicit weights for another number of channels
     * @throws MediaException.Unsupported if the file has no decodable audio track
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.Io if the file cannot be opened or read
     */
    public static LoudnessStats measure(Path file, LoudnessMeterOptions options) {
        Objects.requireNonNull(options, "options");
        try (var source = MediaSource.of(file)) {
            return measure(source, options);
        }
    }

    /**
     * Measures the first audio track of a file on disk and returns the gain that normalizes it.
     *
     * @param file the media file, opened for the call and closed before it returns
     * @param normalization the target and the true-peak ceiling
     * @return the gain in decibels ({@link LoudnessNormalization#gainDb(LoudnessStats)})
     * @throws IllegalArgumentException if the track's sampling rate is below 8 kHz
     * @throws MediaException.Unsupported if the file has no decodable audio track
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.Io if the file cannot be opened or read
     */
    public static double normalizationGainDb(Path file, LoudnessNormalization normalization) {
        Objects.requireNonNull(normalization, "normalization");
        try (var source = MediaSource.of(file)) {
            return normalizationGainDb(source, normalization);
        }
    }

    /**
     * Finds the silences of the first audio track of a file with
     * {@link SilenceDetectorOptions#defaults()}.
     *
     * @param source the input
     * @return the silences in order, unmodifiable
     * @throws MediaException.Unsupported if the input has no decodable audio track
     * @throws MediaException.Malformed if the input is corrupt
     * @throws MediaException.Io if reading the source fails
     */
    public static List<SilenceRange> detectSilence(MediaSource source) {
        return detectSilence(source, SilenceDetectorOptions.defaults());
    }

    /**
     * Finds the silences of the first audio track of a file.
     *
     * <p>A silence is a run of sample frames as {@link SilenceDetector} defines it.
     *
     * @param source the input
     * @param options the threshold and the minimum duration
     * @return the silences in order, unmodifiable
     * @throws MediaException.Unsupported if the input has no decodable audio track
     * @throws MediaException.Malformed if the input is corrupt
     * @throws MediaException.Io if reading the source fails
     */
    public static List<SilenceRange> detectSilence(MediaSource source, SilenceDetectorOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var reader = AudioReader.open(source)) {
            var detector = new SilenceDetector(reader.sampleRate(), options);
            for (var buffer = reader.read(); buffer != null; buffer = reader.read()) {
                detector.accept(buffer);
            }
            return detector.finish();
        }
    }

    /**
     * Finds the silences of the first audio track of a file on disk with
     * {@link SilenceDetectorOptions#defaults()}.
     *
     * @param file the media file, opened for the call and closed before it returns
     * @return the silences in order, unmodifiable
     * @throws MediaException.Unsupported if the file has no decodable audio track
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.Io if the file cannot be opened or read
     */
    public static List<SilenceRange> detectSilence(Path file) {
        return detectSilence(file, SilenceDetectorOptions.defaults());
    }

    /**
     * Finds the silences of the first audio track of a file on disk.
     *
     * @param file the media file, opened for the call and closed before it returns
     * @param options the threshold and the minimum duration
     * @return the silences in order, unmodifiable
     * @throws MediaException.Unsupported if the file has no decodable audio track
     * @throws MediaException.Malformed if the file is corrupt
     * @throws MediaException.Io if the file cannot be opened or read
     */
    public static List<SilenceRange> detectSilence(Path file, SilenceDetectorOptions options) {
        Objects.requireNonNull(options, "options");
        try (var source = MediaSource.of(file)) {
            return detectSilence(source, options);
        }
    }
}
