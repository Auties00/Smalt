package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.internal.tags.TagCarry;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagMode;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Declares the output tracks of a transcode to its muxer once all of them are known, holding the
 * packets that arrive before that.
 *
 * <p>A muxer takes every track before the first packet, but an encoded video track is only known
 * once the first frame is decoded (its size and the encoder's configuration follow from it), while
 * other streams may already produce packets. Track identifiers are reserved up front, in output
 * order; each stream declares its track when it knows it, and once every reserved track is
 * declared the muxer is created, the tracks are added to it in identifier order and the held
 * packets are written. Nothing reaches the output before that, so a transcode that fails while it
 * prepares its streams leaves the output untouched. The tags set by {@link #tags} are given to the muxer
 * once its tracks are added, before the held packets.
 *
 * <p>An instance is not thread-safe.
 */
final class OutputMuxer implements AutoCloseable {
    /**
     * The largest payload held before every track is declared, in bytes.
     */
    private static final long MAX_HELD_BYTES = 64L << 20;

    /**
     * Creates the muxer once every track is declared.
     */
    private final Supplier<Muxer> factory;

    /**
     * The muxer receiving the tracks and packets, or {@code null} before every track is declared.
     */
    private Muxer muxer;

    /**
     * The declared tracks, indexed by identifier minus one.
     */
    private final Track[] tracks;

    /**
     * The packets written before every track was declared, in arrival order.
     */
    private final ArrayDeque<Packet> held;

    /**
     * The payload bytes of {@link #held}.
     */
    private long heldBytes;

    private int reserved;
    private int declared;

    /**
     * Whether the tracks were added to the muxer.
     */
    private boolean started;

    /**
     * The tags of the output, on its terms, or {@code null} to set none.
     */
    private MediaTags tags;

    /**
     * What the output carries, set with {@link #tags}.
     */
    private TagMode tagMode;

    /**
     * The tags of the options, or {@code null}.
     */
    private MediaTags givenTags;

    /**
     * Constructs an instance.
     *
     * @param factory creates the muxer, which this instance then owns
     * @param capacity the number of output tracks
     */
    OutputMuxer(Supplier<Muxer> factory, int capacity) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.tracks = new Track[capacity];
        this.held = new ArrayDeque<>();
    }

    /**
     * Reserves the identifier of the next output track.
     *
     * @return the identifier, counted from one
     * @throws IllegalStateException if every identifier is reserved
     */
    int reserve() {
        if (reserved == tracks.length) {
            throw new IllegalStateException("every output track is reserved");
        }
        return ++reserved;
    }

    /**
     * Declares a reserved track; the last declaration adds every track to the muxer and writes the
     * held packets.
     *
     * @param track the track, whose identifier was reserved
     * @throws IllegalArgumentException if the muxer rejects a track
     * @throws IllegalStateException if the track was already declared
     */
    void declare(Track track) {
        var index = track.id() - 1;
        if (index < 0 || index >= reserved || tracks[index] != null) {
            throw new IllegalStateException("output track " + track.id() + " is not reserved or already declared");
        }
        tracks[index] = track;
        if (++declared == reserved) {
            start();
        }
    }

    /**
     * Sets the tags the muxer receives, as {@link TagCarry#apply} gives them.
     *
     * @param tags the tags on the output's terms
     * @param mode what the output carries
     * @param given the tags of the options, or {@code null}
     * @throws IllegalStateException if the muxer was already created
     */
    void tags(MediaTags tags, TagMode mode, MediaTags given) {
        if (started) {
            throw new IllegalStateException("the tags must be set before the muxer is created");
        }
        this.tags = tags;
        this.tagMode = mode;
        this.givenTags = given;
    }

    /**
     * Creates the muxer, adds every declared track to it, sets the tags and writes the held packets.
     */
    private void start() {
        muxer = factory.get();
        started = true;
        for (var index = 0; index < reserved; index++) {
            muxer.addTrack(tracks[index]);
        }
        if (tags != null) {
            TagCarry.apply(muxer, tags, tagMode, givenTags);
        }
        while (!held.isEmpty()) {
            muxer.writePacket(held.removeFirst());
        }
        heldBytes = 0;
    }

    /**
     * Writes a packet of a declared or reserved track.
     *
     * @param packet the packet, addressed to its output track
     * @throws MediaException.LimitExceeded if more than {@value #MAX_HELD_BYTES} bytes are held
     * before every track is declared
     */
    void write(Packet packet) {
        if (started) {
            muxer.writePacket(packet);
            return;
        }
        heldBytes += packet.size();
        if (heldBytes > MAX_HELD_BYTES) {
            throw new MediaException.LimitExceeded("more than " + MAX_HELD_BYTES + " bytes of media precede the first "
                    + "decodable video frame");
        }
        held.addLast(packet);
    }

    /**
     * Passes the final codec configuration of a declared track to the muxer, as
     * {@link Muxer#updateCodecConfig(int, byte[])} describes; before the muxer exists, the
     * configuration replaces the declared one.
     *
     * @param id the track identifier
     * @param config the final configuration
     */
    void updateCodecConfig(int id, byte[] config) {
        if (started) {
            muxer.updateCodecConfig(id, config);
        } else if (tracks[id - 1] instanceof AudioTrack audio) {
            tracks[id - 1] = audio.toBuilder().codecConfig(config).build();
        }
    }

    /**
     * Completes the output.
     *
     * @throws IllegalStateException if a reserved track was never declared
     */
    void finish() {
        if (!started) {
            throw new IllegalStateException("an output track was never declared");
        }
        muxer.finish();
    }

    /**
     * Releases the muxer, if it was created.
     */
    @Override
    public void close() {
        if (muxer != null) {
            muxer.close();
        }
    }
}
