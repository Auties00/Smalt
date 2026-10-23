package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.util.Objects;

/**
 * Writes the packets of one or more elementary streams into a container.
 *
 * <p>Every track is declared with {@link #addTrack(Track)} before the first packet; the track's
 * {@link Track#id()} is the identifier later packets carry. Packets of each track arrive in
 * decoding order through {@link #writePacket(Packet)}; the muxer interleaves tracks itself.
 * {@link #finish()} completes the file (indexes, trailer); an instance that is closed without
 * finishing leaves incomplete output.
 *
 * @implSpec Implementations must reject a packet for an undeclared track, a track added after the first packet, and a
 * codec the container cannot carry with {@link IllegalArgumentException} or {@link IllegalStateException}, and must
 * translate output failures into {@link MediaException.Io}.
 */
public interface Muxer extends AutoCloseable {
    /**
     * Declares a track.
     *
     * @param track the track description; its id must be unique within this muxer
     * @throws IllegalArgumentException if the id is already used or the container cannot carry the codec
     * @throws IllegalStateException if a packet was already written
     */
    void addTrack(Track track);

    /**
     * Returns whether {@link #setTags(MediaTags)} is supported.
     *
     * @implSpec The default implementation returns {@code false}.
     *
     * @return {@code true} when the container stores tags, pictures and chapters
     */
    default boolean supportsTags() {
        return false;
    }

    /**
     * Sets the tags, attached pictures (cover art) and chapters the output carries.
     *
     * <p>The tags are written in the container's own form: an iTunes {@code ilst} and a Nero
     * {@code chpl} box for MP4 (with a QuickTime chapter track from {@code Mp4Muxer}),
     * {@code Tags}, {@code Chapters} and picture {@code Attachments} for
     * Matroska, Vorbis comments for Ogg and FLAC, ID3v2 for MP3 and ADTS, the {@code LIST/INFO}
     * and {@code id3 } chunks for WAV, the text and {@code ID3 } chunks for AIFF. A later call
     * replaces the tags of an earlier one.
     *
     * @implSpec The default implementation throws {@link MediaException.Unsupported}. Implementations must accept the
     * call until the first packet is written.
     *
     * @param tags the tags, pictures and chapters to write
     * @throws IllegalStateException if a packet was already written
     * @throws MediaException.Unsupported if the container cannot store tags
     */
    default void setTags(MediaTags tags) {
        throw new MediaException.Unsupported("container", getClass().getSimpleName() + " cannot store tags");
    }

    /**
     * Writes one packet.
     *
     * <p>A packet whose side data carries a
     * {@link com.github.auties00.smalt.track.PacketSideData#newCodecConfig() new codec configuration}
     * that differs from the one in use changes the configuration of its track from that packet on:
     * a container that stores several configurations per track stores the new one, a container
     * whose packets can carry it in band (H.264 and HEVC parameter sets, an ADTS header) writes it
     * there, frames that describe themselves (VP8, VP9, AV1) are written as they are, and any other
     * container rejects the packet. Packets flagged
     * {@link com.github.auties00.smalt.track.PacketFlag#DISCARD} are written as any other packet,
     * or marked as decoded but not presented where the container can say so (Matroska).
     *
     * @param packet the next packet of its track, in decoding order
     * @throws IllegalArgumentException if the packet's track was not declared
     * @throws IllegalStateException if the muxer is finished
     * @throws MediaException.Unsupported if the packet changes its track's codec configuration and
     * the container cannot store the change
     * @throws MediaException.Io if writing the output fails
     */
    void writePacket(Packet packet);

    /**
     * Replaces the codec configuration of a declared track with the final form its encoder
     * reports once flushed, such as a FLAC {@code STREAMINFO} that carries the stream's length and
     * MD5 signature, and a filled seek table.
     *
     * <p>Call it after the track's last packet and before {@link #finish()}. A muxer stores the new
     * configuration where it stores the old one when it still can: before writing it, or by
     * rewriting it in place on a seekable sink when both forms have the same length. Otherwise the
     * configuration written first stays, which must therefore be valid on its own.
     *
     * @implSpec The default implementation does nothing.
     *
     * @param trackId the declared track's identifier
     * @param config the final configuration, in the form {@link Track#codecConfig()} describes
     * @throws IllegalArgumentException if the track was not declared
     * @throws MediaException.Io if rewriting the output fails
     */
    default void updateCodecConfig(int trackId, byte[] config) {
        Objects.requireNonNull(config, "config");
    }

    /**
     * Completes the output.
     *
     * @throws IllegalStateException if the muxer is already finished
     * @throws MediaException.Io if writing the output fails
     */
    void finish();

    /**
     * Releases the muxer's resources, including temporary files; the caller's output is not
     * closed.
     */
    @Override
    void close();
}
