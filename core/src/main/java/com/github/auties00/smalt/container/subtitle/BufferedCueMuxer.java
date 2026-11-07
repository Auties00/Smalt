package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.internal.ConfigChanges;
import com.github.auties00.smalt.internal.subtitle.CueMerger;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleDecoder;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Writes a text subtitle file whose layout needs every cue first: the cues of each track are decoded and
 * joined as the packets arrive, and the file is written by {@link #finish()}.
 *
 * <p>Consecutive cues of a track with the same content that follow each other without a gap are joined
 * ({@link CueMerger}), which restores the cues an MP4 track split. The file is UTF-8 with line feeds.
 */
abstract sealed class BufferedCueMuxer implements Muxer permits SamiMuxer, LrcMuxer, MicroDvdMuxer {
    /**
     * The codec the tracks must have.
     */
    private final Codec codec;

    /**
     * The most tracks the file holds.
     */
    private final int maxTracks;

    /**
     * The output.
     */
    private final MediaSink output;

    /**
     * The declared tracks with their cues.
     */
    private final List<Entry> entries = new ArrayList<>();

    /**
     * Whether a packet was written.
     */
    private boolean started;

    /**
     * Whether {@link #finish()} or {@link #close()} ran.
     */
    private boolean finished;

    /**
     * Holds one declared track and its cues.
     *
     * @param track the declared track
     * @param decoder the decoder of its packets
     * @param cues the joined cues
     * @param merger the joiner of its cues
     */
    record Entry(Track track, SubtitleDecoder decoder, List<SubtitleCue> cues, CueMerger merger) {
    }

    /**
     * Constructs a muxer.
     *
     * @param codec the codec of the tracks
     * @param maxTracks the most tracks the file holds
     * @param output the sink, written from its position; not closed by the muxer
     */
    BufferedCueMuxer(Codec codec, int maxTracks, MediaSink output) {
        this.codec = codec;
        this.maxTracks = maxTracks;
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the file holds no more tracks or the codec is not the file's codec
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (started || finished) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (entries.size() >= maxTracks) {
            throw new IllegalArgumentException(maxTracks == 1 ? "a subtitle file holds one track"
                    : "the file holds at most " + maxTracks + " tracks");
        }
        if (track.codec() != codec) {
            throw new IllegalArgumentException(name() + " files cannot carry " + track.codec().displayName());
        }
        for (var entry : entries) {
            if (entry.track().id() == track.id()) {
                throw new IllegalArgumentException("track " + track.id() + " is already declared");
            }
        }
        var cues = new ArrayList<SubtitleCue>();
        entries.add(new Entry(track, new SubtitleDecoder(track), cues, new CueMerger(cues::add)));
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Unsupported if the packet changes the codec configuration
     */
    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        Entry target = null;
        for (var entry : entries) {
            if (entry.track().id() == packet.trackId()) {
                target = entry;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
        }
        ConfigChanges.reject(packet, target.track().codecConfig().orElse(null), "a subtitle file");
        started = true;
        var cue = target.decoder().decode(packet);
        if (cue != null) {
            target.merger().offer(cue);
        }
    }

    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        finished = true;
        for (var entry : entries) {
            entry.merger().finish();
            entry.cues().sort(Comparator.comparingLong(SubtitleCue::startMicros));
        }
        output.write(ByteBuffer.wrap(format(entries).getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public void close() {
        finished = true;
    }

    /**
     * {@return whether a packet was written or the muxer finished}
     */
    final boolean started() {
        return started || finished;
    }

    /**
     * {@return the name of the format, for messages}
     */
    abstract String name();

    /**
     * Formats the file.
     *
     * @param entries the declared tracks with their cues in start order, in the codec's markup
     * @return the file text
     */
    abstract String format(List<Entry> entries);
}
