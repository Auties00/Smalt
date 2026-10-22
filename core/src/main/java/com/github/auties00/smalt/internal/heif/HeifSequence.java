package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.track.Packet;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Selects the tracks of a HEIF image sequence and interleaves their samples: the color track,
 * and the alpha track that references it with {@code auxl} when there is one.
 *
 * <p>The track selection is static. An instance reads the samples of the two tracks from a
 * demuxer, keeping the samples of one track that arrive before the other needs them. Instances
 * are not thread-safe.
 */
public final class HeifSequence {
    /**
     * The demuxer of the file.
     */
    private final Demuxer demuxer;

    /**
     * The identifier of the color track.
     */
    private final int colorTrack;

    /**
     * The identifier of the alpha track, or {@code -1}.
     */
    private final int alphaTrack;

    /**
     * The bounds of the samples held back.
     */
    private final MediaLimits limits;

    /**
     * The format name, for messages.
     */
    private final String format;

    /**
     * The color samples read ahead of their alpha samples.
     */
    private final ArrayDeque<Packet> colorPackets = new ArrayDeque<>();

    /**
     * The alpha samples read ahead of their color samples.
     */
    private final ArrayDeque<Packet> alphaPackets = new ArrayDeque<>();

    /**
     * Whether the demuxer has no more samples.
     */
    private boolean drained;

    /**
     * Constructs the sample reader of a sequence.
     *
     * @param demuxer the demuxer of the file, not closed by this reader
     * @param colorTrack the identifier of the color track
     * @param alphaTrack the identifier of the alpha track, or {@code -1} for none
     * @param limits the bounds; at most {@link MediaLimits#maxFrames()} samples are held back
     * @param format the format name, for messages
     */
    public HeifSequence(Demuxer demuxer, int colorTrack, int alphaTrack, MediaLimits limits, String format) {
        this.demuxer = Objects.requireNonNull(demuxer, "demuxer");
        this.colorTrack = colorTrack;
        this.alphaTrack = alphaTrack;
        this.limits = Objects.requireNonNull(limits, "limits");
        this.format = Objects.requireNonNull(format, "format");
    }

    /**
     * Returns the color track of a sequence.
     *
     * <p>The color track is the first track with a positive identifier and samples whose sample
     * entry {@code codec} accepts and that is not an auxiliary track ({@code auxl} reference).
     * With {@code excludeThumbnails}, a thumbnail track ({@code thmb} reference) is skipped too.
     *
     * @param movie the tracks of the file
     * @param codec accepts the sample entries of the codec, such as {@code "av01"}
     * @param excludeThumbnails whether a thumbnail track is skipped
     * @return the track, or {@code null} when there is none
     */
    public static HeifMovie.Track colorTrack(HeifMovie movie, Predicate<String> codec, boolean excludeThumbnails) {
        for (var track : movie.tracks()) {
            if (usable(track, codec) && !track.references().containsKey("auxl")
                    && !(excludeThumbnails && track.references().containsKey("thmb"))) {
                return track;
            }
        }
        return null;
    }

    /**
     * Returns the alpha track of a color track.
     *
     * <p>The alpha track is the first track with a positive identifier and samples whose sample
     * entry {@code codec} accepts, that references the color track with {@code auxl}, and whose
     * {@code auxi} box, when present, names an alpha URN.
     *
     * @param movie the tracks of the file
     * @param colorId the identifier of the color track
     * @param codec accepts the sample entries of the codec
     * @return the track, or {@code null} when there is none
     */
    public static HeifMovie.Track alphaTrack(HeifMovie movie, int colorId, Predicate<String> codec) {
        for (var track : movie.tracks()) {
            if (!usable(track, codec)) {
                continue;
            }
            var type = track.property(HeifProperty.AuxiliaryType.class);
            if (type != null && !type.isAlpha()) {
                continue;
            }
            if (track.references("auxl", colorId)) {
                return track;
            }
        }
        return null;
    }

    /**
     * Returns whether a track has samples a decoder of a codec reads.
     *
     * @param track the track to test
     * @param codec accepts the sample entries of the codec
     * @return {@code true} for a track with a positive identifier, samples and a sample entry
     * {@code codec} accepts
     */
    private static boolean usable(HeifMovie.Track track, Predicate<String> codec) {
        return track.id() > 0 && track.sampleCount() > 0 && codec.test(track.sampleEntry());
    }

    /**
     * {@return the next sample of the color track, or {@code null} at its end}
     *
     * @throws MediaException.LimitExceeded if more than {@link MediaLimits#maxFrames()} samples
     * would be held back
     */
    public Packet nextColor() {
        return next(colorPackets);
    }

    /**
     * {@return the next sample of the alpha track, or {@code null} at its end or without an alpha
     * track}
     *
     * @throws MediaException.LimitExceeded if more than {@link MediaLimits#maxFrames()} samples
     * would be held back
     */
    public Packet nextAlpha() {
        return alphaTrack < 0 ? null : next(alphaPackets);
    }

    /**
     * Drops the samples held back.
     */
    public void clear() {
        colorPackets.clear();
        alphaPackets.clear();
    }

    /**
     * Seeks the demuxer and drops the samples held back, so that the next samples of both tracks
     * are the ones the demuxer restarts at (see {@link Demuxer#seek(long)}).
     *
     * @param micros the presentation time the demuxer seeks to, in microseconds
     * @throws MediaException.Malformed if the index needed to seek is corrupt
     */
    public void seek(long micros) {
        demuxer.seek(micros);
        clear();
        drained = false;
    }

    /**
     * Returns the next sample of one track, reading ahead until it arrives.
     *
     * @param queue the samples held back for the track
     * @return the sample, or {@code null} at the end of the file
     */
    private Packet next(ArrayDeque<Packet> queue) {
        while (queue.isEmpty() && !drained) {
            var packet = demuxer.read();
            if (packet == null) {
                drained = true;
            } else if (packet.trackId() == colorTrack) {
                colorPackets.add(packet);
            } else if (alphaTrack >= 0 && packet.trackId() == alphaTrack) {
                alphaPackets.add(packet);
            }
            if (colorPackets.size() + alphaPackets.size() > limits.maxFrames()) {
                throw new MediaException.LimitExceeded(format + ": the color and alpha samples are too far apart");
            }
        }
        return queue.poll();
    }
}
