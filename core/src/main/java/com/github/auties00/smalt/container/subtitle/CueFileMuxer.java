package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.internal.subtitle.CueMerger;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleDecoder;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Writes the one track of a text subtitle file, cue by cue, as the packets arrive.
 *
 * <p>Each packet is decoded into a cue ({@link SubtitleDecoder}); consecutive cues with the same
 * content that follow each other without a gap are joined first ({@link CueMerger}), which
 * restores the cues an MP4 track split at the boundaries of overlapping ones. The file is written
 * strictly in order in UTF-8 without a byte order mark, with line feeds, so any sink works, a
 * streaming one included.
 */
abstract sealed class CueFileMuxer implements Muxer permits SubRipMuxer, WebVttMuxer {
    /**
     * The codec the track must have.
     */
    private final Codec codec;

    /**
     * The output.
     */
    private final MediaSink output;

    /**
     * The joiner of split cues.
     */
    private final CueMerger merger;

    /**
     * The declared track, or {@code null}.
     */
    private Track track;

    /**
     * The decoder of the track's packets, or {@code null} before the track is declared.
     */
    private SubtitleDecoder decoder;

    /**
     * Whether the header was written.
     */
    private boolean started;

    /**
     * Whether {@link #finish()} ran.
     */
    private boolean finished;

    /**
     * Constructs a muxer.
     *
     * @param codec the codec of the track
     * @param output the sink, written from its position; not closed by the muxer
     */
    CueFileMuxer(Codec codec, MediaSink output) {
        this.codec = codec;
        this.output = Objects.requireNonNull(output, "output");
        this.merger = new CueMerger(this::writeCue);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if a track is already declared or its codec is not the
     * file's codec
     */
    @Override
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (started || finished) {
            throw new IllegalStateException("tracks must be declared before the first packet");
        }
        if (this.track != null) {
            throw new IllegalArgumentException("a subtitle file holds one track");
        }
        if (track.codec() != codec) {
            throw new IllegalArgumentException(codec.displayName() + " files cannot carry " + track.codec().displayName());
        }
        this.track = track;
        this.decoder = new SubtitleDecoder(track);
    }

    @Override
    public void writePacket(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the muxer is finished");
        }
        if (track == null || packet.trackId() != track.id()) {
            throw new IllegalArgumentException("packet for undeclared track " + packet.trackId());
        }
        start();
        var cue = decoder.decode(packet);
        if (cue != null) {
            merger.offer(cue);
        }
    }

    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the muxer is already finished");
        }
        start();
        merger.finish();
        finished = true;
    }

    @Override
    public void close() {
        finished = true;
    }

    /**
     * Writes the file header once.
     */
    private void start() {
        if (started) {
            return;
        }
        started = true;
        var header = header(track);
        if (!header.isEmpty()) {
            write(header);
        }
    }

    /**
     * Writes one joined cue.
     *
     * @param cue the cue, its overlapping cues already joined
     */
    private void writeCue(SubtitleCue cue) {
        write(format(cue));
    }

    /**
     * Writes text in UTF-8.
     *
     * @param text the text to write
     * @throws MediaException.Io if writing fails
     */
    private void write(String text) {
        output.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Returns the text that starts the file.
     *
     * @param track the declared track, or {@code null} when none was declared
     * @return the header, empty when the format has none
     */
    abstract String header(Track track);

    /**
     * Formats one cue.
     *
     * @param cue the cue, its text in the file's markup
     * @return the cue block with its trailing blank line
     */
    abstract String format(SubtitleCue cue);
}
