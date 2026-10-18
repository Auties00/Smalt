package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.internal.subtitle.CueMerger;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Track;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Reads the cues of one subtitle track of a media file.
 *
 * <p>The reader opens the file's demuxer, selects the first text subtitle track (or the one
 * {@link SubtitleReaderOptions#trackId()} names) and returns its cues one by one through
 * {@link #read()}, in the order the container stores them, which is start order. Consecutive cues
 * with the same content that follow each other without a gap are returned as one cue, which
 * restores the cues an MP4 track split where they overlapped others; a cue whose container block
 * has no duration ends where the next one starts; packets that show nothing (empty 3GPP timed text
 * samples) are skipped. Cue text keeps the track's markup ({@link #markup()}) unless
 * {@link SubtitleReaderOptions#markup()} asks for another.
 *
 * <p>An instance is not thread-safe. The source is not closed by the reader.
 */
public final class SubtitleReader implements AutoCloseable {
    /**
     * The demuxer.
     */
    private final Demuxer demuxer;

    /**
     * The track read.
     */
    private final Track track;

    /**
     * The markup of the returned cues.
     */
    private final SubtitleMarkup markup;

    /**
     * The cues ready to be returned.
     */
    private final ArrayDeque<SubtitleCue> ready;

    /**
     * The decoder of the track's packets.
     */
    private SubtitleDecoder decoder;

    /**
     * The joiner of split cues.
     */
    private CueMerger merger;

    /**
     * Whether the demuxer reached the end.
     */
    private boolean ended;

    /**
     * Constructs a reader over an open demuxer.
     *
     * @param demuxer the demuxer, owned by the reader
     * @param track   the track read
     * @param markup  the markup of the returned cues
     */
    private SubtitleReader(Demuxer demuxer, Track track, SubtitleMarkup markup) {
        this.demuxer = demuxer;
        this.track = track;
        this.markup = markup;
        this.ready = new ArrayDeque<>();
        restart();
    }

    /**
     * Opens the first subtitle track of a file.
     *
     * @param source the input: a SubRip or WebVTT file, or an MP4 or Matroska file with a subtitle
     *               track
     * @return the reader, which the caller closes
     * @throws NullPointerException       if {@code source} is {@code null}
     * @throws MediaException.Unsupported if the input is not a recognized container or has no text
     *                                    subtitle track
     * @throws MediaException.Malformed   if the input is corrupt
     * @throws MediaException.Io          if reading the source fails
     */
    public static SubtitleReader open(MediaSource source) {
        return open(source, SubtitleReaderOptions.defaults());
    }

    /**
     * Opens a subtitle track of a file.
     *
     * @param source  the input
     * @param options the track to read, the markup to convert to and the limits
     * @return the reader, which the caller closes
     * @throws NullPointerException       if an argument is {@code null}
     * @throws MediaException.Unsupported if the input is not a recognized container or lacks the
     *                                    selected text subtitle track
     * @throws MediaException.Malformed   if the input is corrupt
     * @throws MediaException.Io          if reading the source fails
     */
    public static SubtitleReader open(MediaSource source, SubtitleReaderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var demuxer = Containers.openDemuxer(source, DemuxerOptions.builder().limits(options.limits()).build());
        try {
            Track selected = null;
            for (var track : demuxer.tracks()) {
                if (track.codec().isSubtitle()
                        && (options.trackId().isEmpty() || options.trackId().getAsInt() == track.id())) {
                    selected = track;
                    break;
                }
            }
            if (selected == null) {
                throw new MediaException.Unsupported("subtitles", options.trackId().isPresent()
                        ? "the input has no text subtitle track " + options.trackId().getAsInt()
                        : "the input has no text subtitle track");
            }
            return new SubtitleReader(demuxer, selected, options.markup().orElse(Subtitles.markupOf(selected.codec())));
        } catch (RuntimeException exception) {
            demuxer.close();
            throw exception;
        }
    }

    /**
     * Returns the container of the input.
     *
     * @return the format
     */
    public ContainerFormat format() {
        return demuxer.format();
    }

    /**
     * Returns the track read.
     *
     * @return the track
     */
    public Track track() {
        return track;
    }

    /**
     * Returns the markup of the text of the returned cues.
     *
     * @return {@link SubtitleReaderOptions#markup()} when set, otherwise the track's markup
     *         ({@link Subtitles#markupOf(Codec)})
     */
    public SubtitleMarkup markup() {
        return markup;
    }

    /**
     * Returns the next cue.
     *
     * @return the cue, or {@code null} after the last one
     * @throws MediaException.Malformed if the input is corrupt
     * @throws MediaException.Io        if reading the source fails
     */
    public SubtitleCue read() {
        while (ready.isEmpty() && !ended) {
            var packet = demuxer.read();
            if (packet == null) {
                ended = true;
                merger.finish();
                break;
            }
            if (packet.trackId() != track.id()) {
                continue;
            }
            var cue = decoder.decode(packet);
            if (cue != null) {
                merger.offer(Subtitles.convert(cue, decoder.markup(), markup));
            }
        }
        return ready.pollFirst();
    }

    /**
     * Returns whether {@link #seek(long)} is supported.
     *
     * @return {@code true} when the demuxer can seek
     */
    public boolean isSeekable() {
        return demuxer.isSeekable();
    }

    /**
     * Moves to a time: the next cues are those the container returns from there, which include
     * the cues still shown at that time when the container indexes them.
     *
     * @param micros the target time in microseconds
     * @throws MediaException.Unsupported if the container cannot seek
     * @throws MediaException.Io         if reading the source fails
     */
    public void seek(long micros) {
        demuxer.seek(micros);
        restart();
    }

    /**
     * Releases the demuxer; the source is not closed.
     */
    @Override
    public void close() {
        demuxer.close();
    }

    /**
     * Discards the cues in flight and starts decoding afresh.
     */
    private void restart() {
        ready.clear();
        ended = false;
        decoder = new SubtitleDecoder(track);
        merger = new CueMerger(ready::addLast);
    }
}
