package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Splits a container into the packets of its elementary streams.
 *
 * <p>The track list is complete once the demuxer is constructed. {@link #read()} returns the
 * packets of every track interleaved in file order until the end of the input; callers ignore the
 * tracks they do not need.
 *
 * <p>Seeking follows the contract shared by every Smalt reader. {@link #seek(long)} positions the
 * demuxer on key frames: afterwards {@link #read()} returns, for every track, packets starting at
 * the last packet at or before the requested time that a decoder can start from (a key frame, or
 * any packet of a track whose packets are all independent), so a decoder reset at that point
 * decodes correctly and a caller that wants sample-accurate output discards what precedes the
 * requested time. Demuxers that cannot seek report {@code false} from {@link #isSeekable()}.
 *
 * @implSpec
 * Implementations must raise {@link MediaException.Malformed} for a corrupt container rather than
 * any unchecked runtime exception, must bound every size they read from the input before
 * allocating, and must terminate on every input, including cyclic or self-referencing structures.
 */
public interface Demuxer extends AutoCloseable {
    /**
     * Returns the recognized container format.
     *
     * @return the format
     */
    ContainerFormat format();

    /**
     * Returns every elementary stream of the container, in container order.
     *
     * @return the tracks; unmodifiable
     */
    List<Track> tracks();

    /**
     * Returns the container-level metadata tags, such as title or creation time.
     *
     * @return the tags keyed by their container-specific name; unmodifiable
     */
    Map<String, String> metadata();

    /**
     * Returns the metadata tags that describe one track, such as its title or the encoder that
     * produced it.
     *
     * @implSpec The default implementation returns an empty map.
     *
     * @param trackId the identifier of the track
     * @return the tags keyed by their container-specific name; unmodifiable, empty for an unknown
     *         track
     */
    default Map<String, String> trackMetadata(int trackId) {
        return Map.of();
    }

    /**
     * Returns the chapters of the presentation, in start order.
     *
     * @implSpec The default implementation returns an empty list.
     *
     * @return the chapters; unmodifiable
     */
    default List<Chapter> chapters() {
        return List.of();
    }

    /**
     * Returns the pictures embedded in the container, such as cover art, in container order.
     *
     * @implSpec The default implementation returns an empty list.
     *
     * @return the pictures; unmodifiable
     */
    default List<AttachedPicture> attachedPictures() {
        return List.of();
    }

    /**
     * Returns the duration of the whole presentation.
     *
     * @return the duration in microseconds, or {@code -1} when unknown
     */
    long durationMicros();

    /**
     * Reads the next packet in file order.
     *
     * @return the packet, or {@code null} at the end of the input
     * @throws MediaException.Malformed if the container is corrupt
     * @throws MediaException.Io        if reading the source fails
     */
    Packet read();

    /**
     * Returns whether {@link #seek(long)} is supported.
     *
     * @implSpec The default implementation returns {@code false}.
     *
     * @return {@code true} when the demuxer can seek
     */
    default boolean isSeekable() {
        return false;
    }

    /**
     * Positions the demuxer at the key frame at or before a presentation time.
     *
     * <p>After the call, {@link #read()} returns, for each track, packets starting at the last
     * packet whose presentation time is at or before {@code micros} and from which decoding can
     * start; packets of all tracks stay interleaved in file order. A time before the first packet
     * positions at the start of the input, and a time at or past the end of every track makes the
     * next {@link #read()} return {@code null}. Seeking resets nothing in the caller's decoders:
     * a caller that decodes resets or recreates them.
     *
     * @implSpec The default implementation throws {@link MediaException.Unsupported}.
     * Implementations must leave the demuxer usable after a failed seek, positioned where it was.
     *
     * @param micros the presentation time in microseconds
     * @throws MediaException.Unsupported if the demuxer cannot seek
     * @throws MediaException.Malformed   if the index needed to seek is corrupt
     * @throws MediaException.Io          if reading the source fails
     */
    default void seek(long micros) {
        throw new MediaException.Unsupported("seek", format().name().toLowerCase(Locale.ROOT)
                + " demuxing cannot seek");
    }

    /**
     * Releases the demuxer's resources; the underlying source is not closed.
     *
     * <p>The default implementation holds none and does nothing.
     */
    @Override
    default void close() {
    }
}
