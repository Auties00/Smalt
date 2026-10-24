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
import com.github.auties00.smalt.track.TrackKind;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Reads the cues of one subtitle track of a media file.
 *
 * <p>The reader opens the file's demuxer, selects the first text subtitle, TTML or caption track (or the one
 * {@link SubtitleReaderOptions#trackId()} names) and returns its cues one by one through
 * {@link #read()}, in the order the container stores them, which is start order. Consecutive cues
 * with the same content that follow each other without a gap are returned as one cue, which
 * restores the cues an MP4 track split where they overlapped others (and, for TTML, the cues that
 * continue from one document to the next); a cue whose container block has no duration ends where the
 * next one starts; packets that show nothing (empty 3GPP timed text samples) are skipped. Cue text keeps
 * the track's markup ({@link #markup()}) unless {@link SubtitleReaderOptions#markup()} asks for another,
 * in which case it is converted with the whole track in view
 * ({@link SubtitleDecoder#decode(com.github.auties00.smalt.track.Packet, SubtitleMarkup)}): an ASS event
 * gets the look of its style.
 *
 * <p>A caption track (teletext, CEA-608 or CEA-708) is decoded for {@link SubtitleReaderOptions#captionService()}
 * ({@link SubtitleDecoder} describes how captions become cues). With
 * {@link SubtitleReaderOptions#embeddedCaptions()} the reader reads the closed captions a video track carries
 * instead ({@link ClosedCaptions}): those of the video track {@link SubtitleReaderOptions#trackId()} names, or of the
 * first video track whose codec can carry them; a video without captions gives no cue.
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
     * The caption service decoded from a caption track, or {@code null}.
     */
    private final CaptionService service;

    /**
     * The video track whose captions are read, or {@code null}.
     */
    private final Track video;

    /**
     * The extractor of the video track's captions, or {@code null}.
     */
    private CaptionExtractor extractor;

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
    private SubtitleReader(Demuxer demuxer, Track track, Track video, SubtitleMarkup markup, CaptionService service) {
        this.demuxer = demuxer;
        this.track = track;
        this.video = video;
        this.markup = markup;
        this.service = service;
        this.ready = new ArrayDeque<>();
        restart();
    }

    /**
     * Opens the first subtitle track of a file.
     *
     * @param source the input: a SubRip, WebVTT, SSA, ASS, TTML, SAMI, LRC or MicroDVD file, or an MP4,
     *               Matroska or MPEG transport stream file with a subtitle or caption track
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
     * @param options the track to read, the markup to convert to, the text encoding and the limits
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
        var demuxerOptions = DemuxerOptions.builder().limits(options.limits());
        options.charset().ifPresent(demuxerOptions::charset);
        options.subtitleFrameRate().ifPresent(demuxerOptions::subtitleFrameRate);
        var demuxer = Containers.openDemuxer(source, demuxerOptions.build());
        try {
            if (options.embeddedCaptions()) {
                Track video = null;
                for (var track : demuxer.tracks()) {
                    if (track.kind() == TrackKind.VIDEO && ClosedCaptions.carries(track.codec())
                            && (options.trackId().isEmpty() || options.trackId().getAsInt() == track.id())) {
                        video = track;
                        break;
                    }
                }
                if (video == null) {
                    throw new MediaException.Unsupported("closed captions", options.trackId().isPresent()
                            ? "the input has no video track " + options.trackId().getAsInt() + " that can carry captions"
                            : "the input has no video track that can carry captions");
                }
                var captions = ClosedCaptions.track(video);
                return new SubtitleReader(demuxer, captions, video, options.markup().orElse(SubtitleMarkup.SUBRIP),
                        service(options, captions));
            }
            Track selected = null;
            for (var track : demuxer.tracks()) {
                var codec = track.codec();
                if ((codec.isSubtitle() || codec == Codec.TTML || codec.isCaption())
                        && (options.trackId().isEmpty() || options.trackId().getAsInt() == track.id())) {
                    selected = track;
                    break;
                }
            }
            if (selected == null) {
                throw new MediaException.Unsupported("subtitles", options.trackId().isPresent()
                        ? "the input has no text subtitle or caption track " + options.trackId().getAsInt()
                        : "the input has no text subtitle or caption track");
            }
            return new SubtitleReader(demuxer, selected, null, options.markup().orElse(Subtitles.markupOf(selected.codec())),
                    selected.codec().isCaption() ? service(options, selected) : null);
        } catch (RuntimeException exception) {
            demuxer.close();
            throw exception;
        }
    }

    /**
     * Returns the caption service a reader decodes from a caption track.
     *
     * @param options the options of the reader
     * @param track the caption track
     * @return the service the options name when the track can carry it, otherwise the track's default
     */
    private static CaptionService service(SubtitleReaderOptions options, Track track) {
        return options.captionService().filter(chosen -> chosen.readableFrom(track.codec()))
                .orElse(CaptionService.defaultFor(track));
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
     * @return the track; for the captions carried in video, the {@link Codec#CEA_708} track
     * {@link ClosedCaptions#track(Track)} makes of the video track
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
                if (extractor != null) {
                    for (var captions : extractor.flush()) {
                        for (var cue : decoder.decodeAll(captions, markup)) {
                            merger.offer(cue);
                        }
                    }
                }
                for (var cue : decoder.flush(markup)) {
                    merger.offer(cue);
                }
                merger.finish();
                break;
            }
            if (packet.trackId() != track.id()) {
                continue;
            }
            if (extractor != null) {
                for (var captions : extractor.extract(packet)) {
                    for (var cue : decoder.decodeAll(captions, markup)) {
                        merger.offer(cue);
                    }
                }
                continue;
            }
            for (var cue : decoder.decodeAll(packet, markup)) {
                merger.offer(cue);
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
        decoder = new SubtitleDecoder(track, service);
        extractor = video == null ? null : new CaptionExtractor(video);
        merger = new CueMerger(ready::addLast, track.codec() == Codec.TTML);
    }
}
