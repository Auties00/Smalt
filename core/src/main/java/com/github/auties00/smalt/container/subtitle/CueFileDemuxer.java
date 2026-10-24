package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.subtitle.TextFiles;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleEncoder;
import com.github.auties00.smalt.subtitle.Subtitles;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Demuxes a text subtitle file that is read whole: one track (one per language of a SAMI file) holding
 * every cue in start order.
 *
 * <p>The file is decoded ({@link TextFiles}) and parsed at construction, and its cues sorted by
 * start time, which subtitle files do not guarantee; packets are then served from memory, so
 * seeking is exact and cheap. The tracks have a millisecond time base.
 */
abstract sealed class CueFileDemuxer implements Demuxer
        permits SubRipDemuxer, WebVttDemuxer, AssDemuxer, TtmlDemuxer, SamiDemuxer, LrcDemuxer, MicroDvdDemuxer {
    /**
     * The tracks.
     */
    private final List<Track> tracks;

    /**
     * The cues of the first track in start order.
     */
    private final List<SubtitleCue> cues;

    /**
     * The packets of every track in start order.
     */
    private final Packet[] packets;

    /**
     * The start of each packet in microseconds.
     */
    private final long[] starts;

    /**
     * The end of each packet in microseconds.
     */
    private final long[] ends;

    /**
     * The container-level tags.
     */
    private final Map<String, String> metadata;

    /**
     * The end of the last packet in microseconds.
     */
    private final long duration;

    /**
     * The index of the next packet.
     */
    private int next;

    /**
     * The tags of each track, by track identifier.
     */
    private final Map<Integer, Map<String, String>> trackTags;

    /**
     * Holds one track and its cues.
     *
     * @param track the declared track
     * @param cues the cues in start order, their text in the codec's markup
     * @param tags the tags of the track
     */
    record Stream(DataTrack track, List<SubtitleCue> cues, Map<String, String> tags) {
    }

    /**
     * Constructs a demuxer from the parsed cues of one track.
     *
     * @param codec the track codec
     * @param config the track configuration, or {@code null}
     * @param cues the cues in start order, their text in the codec's markup
     */
    CueFileDemuxer(Codec codec, byte[] config, List<SubtitleCue> cues) {
        this(List.of(new Stream(SubtitleEncoder.track(codec, 1).toBuilder().codecConfig(config).build(), cues, Map.of())),
                Map.of());
    }

    /**
     * Constructs a demuxer from the parsed cues of several tracks.
     *
     * @param streams the tracks and their cues, in track order
     * @param metadata the container-level tags
     */
    CueFileDemuxer(List<Stream> streams, Map<String, String> metadata) {
        var all = new ArrayList<Packet>();
        var trackList = new ArrayList<Track>();
        var tags = new HashMap<Integer, Map<String, String>>();
        var duration = 0L;
        for (var stream : streams) {
            var end = 0L;
            for (var cue : stream.cues()) {
                end = Math.max(end, cue.endMicros());
            }
            duration = Math.max(duration, end);
            var track = stream.track().toBuilder().duration(stream.cues().isEmpty() ? 0 : (end + 500) / 1000).build();
            trackList.add(track);
            if (!stream.tags().isEmpty()) {
                tags.put(track.id(), Map.copyOf(stream.tags()));
            }
            var encoder = new SubtitleEncoder(track);
            for (var cue : stream.cues()) {
                all.add(encoder.encode(cue, Subtitles.markupOf(track.codec())));
            }
        }
        this.cues = streams.isEmpty() ? List.of() : List.copyOf(streams.getFirst().cues());
        this.trackTags = Map.copyOf(tags);
        this.tracks = List.copyOf(trackList);
        this.metadata = Map.copyOf(metadata);
        this.duration = duration;
        var timeBase = trackList.isEmpty() ? null : trackList.getFirst().timeBase();
        all.sort(Comparator.comparingLong(Packet::pts));
        this.packets = all.toArray(new Packet[0]);
        this.starts = new long[packets.length];
        this.ends = new long[packets.length];
        for (var index = 0; index < packets.length; index++) {
            starts[index] = Objects.requireNonNull(timeBase).toMicros(packets[index].pts());
            ends[index] = timeBase.toMicros(packets[index].pts() + packets[index].duration());
        }
    }

    /**
     * Constructs a demuxer over packets made by the subclass, such as the events of an ASS file in their
     * read order or the one document of a TTML file.
     *
     * @param track the declared track
     * @param packets the packets in start order
     * @param cues the cues of the track in start order, in the codec's markup
     * @param metadata the container-level tags
     * @param duration the end of the last packet in microseconds
     */
    CueFileDemuxer(DataTrack track, List<Packet> packets, List<SubtitleCue> cues, Map<String, String> metadata,
                   long duration) {
        this.tracks = List.of(track);
        this.cues = List.copyOf(cues);
        this.trackTags = Map.of();
        this.metadata = Map.copyOf(metadata);
        this.duration = duration;
        this.packets = packets.toArray(new Packet[0]);
        this.starts = new long[this.packets.length];
        this.ends = new long[this.packets.length];
        for (var index = 0; index < this.packets.length; index++) {
            starts[index] = track.timeBase().toMicros(this.packets[index].pts());
            ends[index] = track.timeBase().toMicros(this.packets[index].pts() + this.packets[index].duration());
        }
    }

    /**
     * Reads and decodes a whole text file.
     *
     * @param source the input
     * @param options the limits bounding the file size and the text encoding
     * @return the text, with line feeds only and without a byte order mark
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow or than an
     * array can hold
     * @throws MediaException.Malformed if the source ends before its declared size
     * @throws MediaException.Io if reading the source fails
     */
    static String readText(MediaSource source, DemuxerOptions options) {
        return TextFiles.decode(readBytes(source, options), options.charset().orElse(null));
    }

    /**
     * Reads a whole file.
     *
     * @param source the input
     * @param options the limits bounding the file size
     * @return the bytes
     * @throws MediaException.LimitExceeded if the file is larger than the limits allow or than an
     * array can hold
     * @throws MediaException.Malformed if the source ends before its declared size
     * @throws MediaException.Io if reading the source fails
     */
    static byte[] readBytes(MediaSource source, DemuxerOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var size = source.size();
        if (size > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("subtitle file of " + size + " bytes is too large");
        }
        return SourceBytes.readAll(source, options.limits(), false);
    }

    /**
     * Returns the cues of the first track in start order.
     *
     * @return the cues in the track's markup, unmodifiable
     */
    public List<SubtitleCue> cues() {
        return cues;
    }

    @Override
    public List<Track> tracks() {
        return tracks;
    }

    @Override
    public Map<String, String> metadata() {
        return metadata;
    }

    @Override
    public Map<String, String> trackMetadata(int trackId) {
        return trackTags.getOrDefault(trackId, Map.of());
    }

    @Override
    public long durationMicros() {
        return duration;
    }

    @Override
    public Packet read() {
        return next < packets.length ? packets[next++] : null;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The next packet is the first cue that is still shown at the target, or the first cue
     * that starts after it; a negative target restarts from the first cue.
     */
    @Override
    public void seek(long micros) {
        var index = 0;
        while (index < packets.length && ends[index] <= micros && starts[index] < micros) {
            index++;
        }
        next = index;
    }
}
