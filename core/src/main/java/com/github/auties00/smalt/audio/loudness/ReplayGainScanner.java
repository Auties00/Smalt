package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Computes the ReplayGain 2.0 gains of the tracks of an album.
 *
 * <p>Each track is measured by its own {@link LoudnessMeter}, with the true peak on, either from a
 * file ({@link #addTrack(MediaSource)}) or from a meter the caller fed ({@link #addTrack(LoudnessMeter)}).
 * {@link #results()} then returns one {@link ReplayGain} per track in the order they were added, each with
 * its own track values and the album values shared by all: the album loudness gates the 400 ms blocks of
 * every track together, as if the tracks were played back to back, and the album peak is the largest
 * track peak. Memory stays bounded whatever the number and length of the tracks.
 *
 * <p>Instances are not thread-safe.
 */
public final class ReplayGainScanner {
    /**
     * The gains of the tracks added so far, without album values.
     */
    private final List<ReplayGain> tracks;

    /**
     * The block counts of the album histogram.
     */
    private final long[] counts;

    /**
     * The summed block energies of the album histogram.
     */
    private final double[] energies;

    /**
     * The largest track peak so far.
     */
    private double peak;

    /**
     * Constructs a scanner with no track.
     */
    public ReplayGainScanner() {
        this.tracks = new ArrayList<>();
        this.counts = new long[LoudnessMeter.histogramBins()];
        this.energies = new double[LoudnessMeter.histogramBins()];
    }

    /**
     * Measures the first audio track of a file and adds it to the album.
     *
     * <p>The track is decoded at its own rate and layout through {@link AudioReader}.
     *
     * @param source the input, read and not closed
     * @return the track's gains, without album values
     * @throws IllegalArgumentException if the track's sampling rate is below 8 kHz
     * @throws MediaException.Unsupported if the input has no decodable audio track
     * @throws MediaException.Malformed if the input is corrupt
     * @throws MediaException.Io if reading the source fails
     */
    public ReplayGain addTrack(MediaSource source) {
        Objects.requireNonNull(source, "source");
        try (var reader = AudioReader.open(source)) {
            var meter = new LoudnessMeter(reader.layout(), reader.sampleRate());
            for (AudioBuffer buffer = reader.read(); buffer != null; buffer = reader.read()) {
                meter.accept(buffer);
            }
            return addTrack(meter);
        }
    }

    /**
     * Adds a track measured by the caller to the album.
     *
     * <p>The meter is read, not kept: feeding it more samples later does not change the album.
     *
     * @param meter the meter that measured the whole track
     * @return the track's gains, without album values; the peak is the sample peak when the meter
     * leaves the true peak out
     */
    public ReplayGain addTrack(LoudnessMeter meter) {
        Objects.requireNonNull(meter, "meter");
        var track = ReplayGain.ofTrack(meter.stats());
        meter.addBlocksTo(counts, energies);
        if (!Double.isNaN(track.trackPeak())) {
            peak = Math.max(peak, track.trackPeak());
        }
        tracks.add(track);
        return track;
    }

    /**
     * {@return the number of tracks added}
     */
    public int trackCount() {
        return tracks.size();
    }

    /**
     * {@return the album gain of the tracks added so far, in dB, or {@code NaN} when they are all
     * silent or there are none}
     */
    public double albumGainDb() {
        return ReplayGain.gainOf(LoudnessMeter.gatedLoudness(counts, energies));
    }

    /**
     * {@return the album peak of the tracks added so far, 0 when there are none}
     */
    public double albumPeak() {
        return peak;
    }

    /**
     * Returns the gains of every track added, each with the album values.
     *
     * @return one entry per track in the order added, unmodifiable
     */
    public List<ReplayGain> results() {
        var gain = albumGainDb();
        var albumPeak = tracks.isEmpty() ? Double.NaN : peak;
        var result = new ArrayList<ReplayGain>(tracks.size());
        for (var track : tracks) {
            result.add(track.withAlbum(gain, albumPeak));
        }
        return List.copyOf(result);
    }
}
