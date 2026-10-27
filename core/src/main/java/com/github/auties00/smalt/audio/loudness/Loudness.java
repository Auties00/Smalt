package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.track.Codec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Measures the loudness of media files, computes the gains that normalize them, tags them with
 * their ReplayGain, and finds their silences.
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
    /**
     * Computes the ReplayGain 2.0 gains of the tracks of an album.
     *
     * <p>Each file's first audio track is measured as {@link ReplayGainScanner} describes; pass a
     * single file to get album values equal to its track values.
     *
     * @param album the media files, in album order, each opened for the call and closed before it
     * returns
     * @return one entry per file in the same order, unmodifiable
     * @throws NullPointerException if a file is {@code null}
     * @throws IllegalArgumentException if a track's sampling rate is below 8 kHz
     * @throws MediaException.Unsupported if a file has no decodable audio track
     * @throws MediaException.Malformed if a file is corrupt
     * @throws MediaException.Io if a file cannot be opened or read
     */
    public static List<ReplayGain> replayGain(List<Path> album) {
        var scanner = new ReplayGainScanner();
        for (var file : List.copyOf(album)) {
            try (var source = MediaSource.of(file)) {
                scanner.addTrack(source);
            }
        }
        return scanner.results();
    }

    /**
     * Computes the ReplayGain 2.0 gains of the tracks of an album and writes them into each file's tags.
     *
     * <p>The gains are those of {@link #replayGain(List)}. An Opus file gets the
     * {@link ReplayGain.TagFormat#R128} fields, as RFC 7845 asks; every other file gets the
     * {@link ReplayGain.TagFormat#REPLAYGAIN} fields. Either way the gain fields of the other form are
     * removed and every other tag, picture and chapter is kept, through {@link TagEditor}. Every file is
     * measured before the first is written.
     *
     * @param album the media files, in album order
     * @return one entry per file in the same order, unmodifiable
     * @throws NullPointerException if a file is {@code null}
     * @throws IllegalArgumentException if a track's sampling rate is below 8 kHz
     * @throws MediaException.Unsupported if a file has no decodable audio track or its container's
     * tags cannot be written ({@link TagEditor#isSupported})
     * @throws MediaException.Malformed if a file or its metadata is corrupt
     * @throws MediaException.Io if a file cannot be read or written
     */
    public static List<ReplayGain> writeReplayGain(List<Path> album) {
        var files = List.copyOf(album);
        var formats = new ArrayList<ReplayGain.TagFormat>(files.size());
        for (var file : files) {
            try (var source = MediaSource.of(file); var reader = AudioReader.open(source)) {
                if (!TagEditor.isSupported(reader.format())) {
                    throw new MediaException.Unsupported("tags", "the tags of " + reader.format() + " files cannot be "
                            + "written");
                }
                formats.add(reader.track().codec() == Codec.OPUS ? ReplayGain.TagFormat.R128
                        : ReplayGain.TagFormat.REPLAYGAIN);
            }
        }
        var gains = replayGain(files);
        for (var index = 0; index < files.size(); index++) {
            var file = files.get(index);
            TagEditor.write(file, gains.get(index).toTags(TagEditor.read(file), formats.get(index)));
        }
        return gains;
    }
}
